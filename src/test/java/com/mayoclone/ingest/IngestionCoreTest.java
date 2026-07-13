package com.mayoclone.ingest;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IngestFailure;
import com.mayoclone.domain.IngestFailureReason;
import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.OrderItem;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.parser.IrctcEmailParser;
import com.mayoclone.parser.ParsedOrder;
import com.mayoclone.repository.IngestFailureRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.service.AggregatorService;
import com.mayoclone.service.OrderCommandService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the shared pipeline routes un-ingestable mail to the review queue
 * (dead-letter) with the right reason, and stores a clean order on success.
 */
class IngestionCoreTest {

    private IrctcOrderRepository orderRepo;
    private AggregatorService aggregatorService;
    private IngestFailureRepository failureRepo;
    private IrctcEmailParser parser;
    private IngestionCore core;

    @BeforeEach
    void setUp() {
        orderRepo = mock(IrctcOrderRepository.class);
        aggregatorService = mock(AggregatorService.class);
        failureRepo = mock(IngestFailureRepository.class);
        parser = mock(IrctcEmailParser.class);
        AppMetrics metrics = new AppMetrics(new SimpleMeterRegistry());
        OrderCommandService orderCommandService = mock(OrderCommandService.class);
        com.mayoclone.jobs.JobQueue jobQueue = mock(com.mayoclone.jobs.JobQueue.class);
        // Enrichment OFF → the enqueue block is inert (current behavior unchanged).
        com.mayoclone.enrich.EnrichmentProperties enrichProps =
                new com.mayoclone.enrich.EnrichmentProperties(false, "IRCTC_ECATERING", 1500, "", "", "");
        core = new IngestionCore(List.of(parser), orderRepo, aggregatorService, failureRepo, metrics,
                orderCommandService, jobQueue, enrichProps);
    }

    private static RawMessage msg() {
        return new RawMessage("orders@zoopindia.com", "Order", "body", "<mid@x>");
    }

    @Test
    void noAggregatorMatchIsDeadLettered() {
        when(aggregatorService.findBySender(any())).thenReturn(Optional.empty());

        IngestResult result = core.process(5L, 9L, MailSourceType.FORWARDING, msg());

        assertEquals(0, result.newOrders());
        ArgumentCaptor<IngestFailure> captor = ArgumentCaptor.forClass(IngestFailure.class);
        verify(failureRepo).save(captor.capture());
        assertEquals(IngestFailureReason.NO_AGGREGATOR_MATCH, captor.getValue().getReason());
        assertEquals(5L, captor.getValue().getAccountId());
        verify(orderRepo, never()).save(any());
    }

    @Test
    void noParserIsDeadLettered() {
        Aggregator agg = agg();
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(false);

        core.process(1L, null, MailSourceType.IMAP, msg());

        ArgumentCaptor<IngestFailure> captor = ArgumentCaptor.forClass(IngestFailure.class);
        verify(failureRepo).save(captor.capture());
        assertEquals(IngestFailureReason.NO_PARSER, captor.getValue().getReason());
    }

    @Test
    void parserExceptionIsDeadLetteredAsParseFailed() {
        Aggregator agg = agg();
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(true);
        when(parser.parse(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        core.process(1L, null, MailSourceType.IMAP, msg());

        ArgumentCaptor<IngestFailure> captor = ArgumentCaptor.forClass(IngestFailure.class);
        verify(failureRepo).save(captor.capture());
        assertEquals(IngestFailureReason.PARSE_FAILED, captor.getValue().getReason());
    }

    @Test
    void successStoresOrderAndDoesNotDeadLetter() {
        Aggregator agg = agg();
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(true);
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(parsed());
        when(orderRepo.existsBySourceMessageId(any())).thenReturn(false);
        when(orderRepo.existsByAggregatorAndExternalOrderId(any(), any())).thenReturn(false);

        IngestResult result = core.process(3L, 4L, MailSourceType.GMAIL_OAUTH, msg());

        assertEquals(1, result.newOrders());
        verify(orderRepo).save(any(IrctcOrder.class));
        verify(failureRepo, never()).save(any());
    }

    @Test
    void duplicateBySourceMessageIdIsNotStored() {
        Aggregator agg = agg();
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(true);
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(parsed());
        when(orderRepo.existsBySourceMessageId(any())).thenReturn(true);

        IngestResult result = core.process(3L, 4L, MailSourceType.IMAP, msg());

        assertEquals(0, result.newOrders());
        verify(orderRepo, never()).save(any());
        verify(failureRepo, never()).save(any());
    }

    @Test
    void orderWithNoItemsIsStillSavedButFlaggedForReview() {
        Aggregator agg = agg();
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(true);
        // parsed() carries an empty items list.
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(parsed());
        when(orderRepo.existsBySourceMessageId(any())).thenReturn(false);
        when(orderRepo.existsByAggregatorAndExternalOrderId(any(), any())).thenReturn(false);

        core.process(3L, 4L, MailSourceType.IMAP, msg());

        ArgumentCaptor<IrctcOrder> captor = ArgumentCaptor.forClass(IrctcOrder.class);
        verify(orderRepo).save(captor.capture());
        IrctcOrder saved = captor.getValue();
        assertTrue(saved.isNeedsReview(), "an order with no items must be flagged, not dropped");
        assertTrue(saved.getReviewReason().contains("no items parsed"), saved.getReviewReason());
        // Ultimate safety net: the full raw body is retained on the order.
        assertEquals("body", saved.getRawEmail());
    }

    @Test
    void orderWithZeroAmountIsStillSavedButFlaggedForReview() {
        Aggregator agg = agg();
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(true);
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(
                withItemsAndAmount(List.of(new OrderItem("Veg Biryani", 1, new BigDecimal("180"))),
                        BigDecimal.ZERO));
        when(orderRepo.existsBySourceMessageId(any())).thenReturn(false);
        when(orderRepo.existsByAggregatorAndExternalOrderId(any(), any())).thenReturn(false);

        core.process(3L, 4L, MailSourceType.IMAP, msg());

        ArgumentCaptor<IrctcOrder> captor = ArgumentCaptor.forClass(IrctcOrder.class);
        verify(orderRepo).save(captor.capture());
        IrctcOrder saved = captor.getValue();
        assertTrue(saved.isNeedsReview());
        assertTrue(saved.getReviewReason().contains("amount missing/zero"), saved.getReviewReason());
    }

    @Test
    void generatedOrderIdIsFlaggedForReview() {
        Aggregator agg = agg();
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(true);
        // externalOrderId of the synthesized <AGGCODE>-<digits> shape.
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(
                new ParsedOrder("ZOOP", "ZOOP-123456", "1234567890", "12951", "Rajdhani",
                        "B3", "32", "BCT", "NDLS", "New Delhi", "Rajesh", "9876543210",
                        null, "13:00-13:30", new BigDecimal("450"), "INR", "CONFIRMED",
                        List.of(new OrderItem("Veg Biryani", 1, new BigDecimal("180"))),
                        "Order", "<mid@x>", "PREPAID", null, null, null, null, null));
        when(orderRepo.existsBySourceMessageId(any())).thenReturn(false);
        when(orderRepo.existsByAggregatorAndExternalOrderId(any(), any())).thenReturn(false);

        core.process(3L, 4L, MailSourceType.IMAP, msg());

        ArgumentCaptor<IrctcOrder> captor = ArgumentCaptor.forClass(IrctcOrder.class);
        verify(orderRepo).save(captor.capture());
        assertTrue(captor.getValue().isNeedsReview());
        assertTrue(captor.getValue().getReviewReason().contains("order id not found (generated)"),
                captor.getValue().getReviewReason());
    }

    @Test
    void cleanOrderIsNotFlaggedForReview() {
        Aggregator agg = agg();
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(true);
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(
                withItemsAndAmount(List.of(new OrderItem("Veg Biryani", 2, new BigDecimal("180"))),
                        new BigDecimal("360")));
        when(orderRepo.existsBySourceMessageId(any())).thenReturn(false);
        when(orderRepo.existsByAggregatorAndExternalOrderId(any(), any())).thenReturn(false);

        core.process(3L, 4L, MailSourceType.IMAP, msg());

        ArgumentCaptor<IrctcOrder> captor = ArgumentCaptor.forClass(IrctcOrder.class);
        verify(orderRepo).save(captor.capture());
        assertFalse(captor.getValue().isNeedsReview(), "a complete parse must NOT be flagged");
        assertEquals(null, captor.getValue().getReviewReason());
    }

    private static Aggregator agg() {
        Aggregator a = new Aggregator();
        a.setCode("ZOOP");
        return a;
    }

    private static ParsedOrder parsed() {
        return new ParsedOrder("ZOOP", "EXT-1", "1234567890", "12951", "Rajdhani",
                "B3", "32", "BCT", "NDLS", "New Delhi", "Rajesh", "9876543210",
                null, "13:00-13:30", new BigDecimal("450"), "INR", "CONFIRMED",
                List.of(), "Order", "<mid@x>", "PREPAID", null, null, null, null, null);
    }

    /** A complete parse (real order id, train, station, passenger) with tunable items/amount. */
    private static ParsedOrder withItemsAndAmount(List<OrderItem> items, BigDecimal amount) {
        return new ParsedOrder("ZOOP", "EXT-9", "1234567890", "12951", "Rajdhani",
                "B3", "32", "BCT", "NDLS", "New Delhi", "Rajesh", "9876543210",
                null, "13:00-13:30", amount, "INR", "CONFIRMED",
                items, "Order", "<mid@x>", "PREPAID", null, null, null, null, null);
    }
}
