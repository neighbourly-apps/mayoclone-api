package com.mayoclone.ingest;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IngestFailure;
import com.mayoclone.domain.IngestFailureReason;
import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.MailSourceType;
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
        core = new IngestionCore(List.of(parser), orderRepo, aggregatorService, failureRepo, metrics,
                orderCommandService);
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

    private static Aggregator agg() {
        Aggregator a = new Aggregator();
        a.setCode("ZOOP");
        return a;
    }

    private static ParsedOrder parsed() {
        return new ParsedOrder("ZOOP", "EXT-1", "1234567890", "12951", "Rajdhani",
                "B3", "32", "BCT", "NDLS", "New Delhi", "Rajesh", "9876543210",
                null, "13:00-13:30", new BigDecimal("450"), "INR", "CONFIRMED",
                List.of(), "Order", "<mid@x>");
    }
}
