package com.mayoclone.ingest;

import com.mayoclone.domain.Aggregator;
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
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit for the order-uniqueness + global field guards:
 * (a) re-processing the same RawMessage twice creates exactly ONE order (idempotent),
 * (b) a message with no Message-ID still dedups on the STABLE fallback id,
 * (c) a garbage trainNumber ("EXP 123") is stored digits-only or null — never text,
 * (d) a junk externalOrderId ("for") is replaced by the generated id and flagged.
 */
class OrderDedupGuardsTest {

    private IrctcOrderRepository orderRepo;
    private AggregatorService aggregatorService;
    private IrctcEmailParser parser;
    private IngestionCore core;

    @BeforeEach
    void setUp() {
        orderRepo = mock(IrctcOrderRepository.class);
        aggregatorService = mock(AggregatorService.class);
        IngestFailureRepository failureRepo = mock(IngestFailureRepository.class);
        parser = mock(IrctcEmailParser.class);
        AppMetrics metrics = new AppMetrics(new SimpleMeterRegistry());
        OrderCommandService orderCommandService = mock(OrderCommandService.class);
        com.mayoclone.jobs.JobQueue jobQueue = mock(com.mayoclone.jobs.JobQueue.class);
        com.mayoclone.enrich.EnrichmentProperties enrichProps =
                new com.mayoclone.enrich.EnrichmentProperties(false, "IRCTC_ECATERING", 1500, "", "", "");
        core = new IngestionCore(List.of(parser), orderRepo, aggregatorService, failureRepo, metrics,
                orderCommandService, jobQueue, enrichProps,
                mock(com.mayoclone.service.TrainNameService.class));

        Aggregator agg = new Aggregator();
        agg.setCode("ZOOP");
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(true);
    }

    /** Wire a STATEFUL repo: existsBySourceMessageId reflects what save() actually persisted. */
    private void wireStatefulRepo() {
        Set<String> stored = new HashSet<>();
        when(orderRepo.existsBySourceMessageId(any()))
                .thenAnswer(inv -> stored.contains(inv.<String>getArgument(0)));
        when(orderRepo.existsByAggregatorAndExternalOrderId(any(), any())).thenReturn(false);
        when(orderRepo.save(any(IrctcOrder.class))).thenAnswer(inv -> {
            IrctcOrder o = inv.getArgument(0);
            stored.add(o.getSourceMessageId());
            return o;
        });
    }

    /** Parser echoes the inbound messageId (arg 4) into sourceMessageId, like the real parsers. */
    private void parserEchoesMessageId(String externalOrderId, String trainNumber, String trainName) {
        when(parser.parse(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            String messageId = inv.getArgument(4);
            return new ParsedOrder("ZOOP", externalOrderId, "1234567890", trainNumber, trainName,
                    "B3", "32", "BCT", "NDLS", "New Delhi", "Rajesh", "9876543210",
                    null, "13:00-13:30", new BigDecimal("450"), "INR", "CONFIRMED",
                    List.of(), "Order", messageId, "PREPAID", null, null, null, null, null);
        });
    }

    @Test
    void reprocessingSameMessageStoresExactlyOneOrder() {
        wireStatefulRepo();
        parserEchoesMessageId("ORD-555", "12951", "Rajdhani");
        RawMessage msg = new RawMessage("orders@zoopindia.com", "Order", "body", "<mid-abc@x>");

        IngestResult first = core.process(7L, 3L, MailSourceType.IMAP, msg);
        IngestResult second = core.process(7L, 3L, MailSourceType.IMAP, msg);

        assertEquals(1, first.newOrders(), "first ingest stores the order");
        assertEquals(0, second.newOrders(), "second ingest of the SAME email is idempotent");
        verify(orderRepo, times(1)).save(any(IrctcOrder.class));
    }

    @Test
    void messageWithNoMessageIdDedupsOnStableFallback() {
        wireStatefulRepo();
        parserEchoesMessageId("ORD-777", "12951", "Rajdhani");
        // Simulates the id ImapMailSource.stableMessageId produces when no Message-ID header
        // exists — a STABLE "gen-..." hash, identical for the same email on every re-sync.
        String fallbackId = "gen-123456789";
        RawMessage a = new RawMessage("orders@zoopindia.com", "Order", "body", fallbackId);
        RawMessage b = new RawMessage("orders@zoopindia.com", "Order", "body", fallbackId);

        assertEquals(1, core.process(7L, 3L, MailSourceType.IMAP, a).newOrders());
        assertEquals(0, core.process(7L, 3L, MailSourceType.IMAP, b).newOrders());
        verify(orderRepo, times(1)).save(any(IrctcOrder.class));
    }

    @Test
    void imapStableMessageIdFallbackIsStableAndNotRandom() throws Exception {
        ImapMailSource src = new ImapMailSource(30, 200, 0, 0);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        mime.setSubject("Your order is confirmed");
        mime.setSentDate(new Date(1_700_000_000_000L));
        // No Message-ID header is set → getMessageID() is null → the fallback path runs.

        String id1 = src.stableMessageId((Message) mime, "orders@zoopindia.com", "Your order is confirmed");
        String id2 = src.stableMessageId((Message) mime, "orders@zoopindia.com", "Your order is confirmed");

        assertNotNull(id1);
        assertTrue(id1.startsWith("gen-"), "fallback id must be the stable gen- hash, not a UUID: " + id1);
        assertEquals(id1, id2, "same email must yield the SAME id across re-syncs (never random)");
    }

    @Test
    void garbageTrainNumberIsStoredDigitsOnlyOrNull() {
        when(orderRepo.existsBySourceMessageId(any())).thenReturn(false);
        when(orderRepo.existsByAggregatorAndExternalOrderId(any(), any())).thenReturn(false);
        when(orderRepo.save(any(IrctcOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        // trainNumber="EXP 123", trainName=null — a train NAME leaked into the number field.
        parserEchoesMessageId("ORD-9", "EXP 123", null);

        core.process(7L, 3L, MailSourceType.IMAP,
                new RawMessage("orders@zoopindia.com", "Order", "body", "<mid-tn@x>"));

        ArgumentCaptor<IrctcOrder> captor = ArgumentCaptor.forClass(IrctcOrder.class);
        verify(orderRepo).save(captor.capture());
        IrctcOrder saved = captor.getValue();
        String tn = saved.getTrainNumber();
        assertNotEquals("EXP 123", tn, "text must never be stored in train number");
        assertTrue(tn == null || tn.chars().allMatch(Character::isDigit),
                "train number must be digits-only or null, was: " + tn);
        // The captured name is preserved rather than discarded.
        assertEquals("EXP 123", saved.getTrainName(), "the leaked train name is kept in trainName");
    }

    @Test
    void junkExternalOrderIdIsReplacedByGeneratedIdAndFlagged() {
        when(orderRepo.existsBySourceMessageId(any())).thenReturn(false);
        when(orderRepo.existsByAggregatorAndExternalOrderId(any(), any())).thenReturn(false);
        when(orderRepo.save(any(IrctcOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        // externalOrderId="for" — a bare dictionary word, not a real order id.
        parserEchoesMessageId("for", "12951", "Rajdhani");

        core.process(7L, 3L, MailSourceType.IMAP,
                new RawMessage("orders@zoopindia.com", "Order", "body", "<mid-eoid@x>"));

        ArgumentCaptor<IrctcOrder> captor = ArgumentCaptor.forClass(IrctcOrder.class);
        verify(orderRepo).save(captor.capture());
        IrctcOrder saved = captor.getValue();
        assertNotEquals("for", saved.getExternalOrderId(), "a junk order id must be replaced");
        assertTrue(saved.getExternalOrderId().matches("^ZOOP-\\d+$"),
                "replacement must be the generated <AGGCODE>-<hash> shape, was: " + saved.getExternalOrderId());
        assertTrue(saved.isNeedsReview(), "a generated order id must be flagged for review");
        assertTrue(saved.getReviewReason().contains("order id not found (generated)"),
                saved.getReviewReason());
    }
}
