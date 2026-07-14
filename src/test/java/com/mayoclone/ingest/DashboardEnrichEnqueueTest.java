package com.mayoclone.ingest;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.OrderItem;
import com.mayoclone.enrich.EnrichmentProperties;
import com.mayoclone.jobs.DashboardEnrichJobHandler;
import com.mayoclone.jobs.JobQueue;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dashboard-enrichment enqueue in {@link IngestionCore} is GATED by
 * {@code mayoclone.enrich.enabled}. Off → no job (byte-for-byte current behavior);
 * on → a DASHBOARD_ENRICH job is coalesced per order id, but only when the order has a
 * vendor and no real passenger name.
 */
class DashboardEnrichEnqueueTest {

    private IrctcOrderRepository orderRepo;
    private AggregatorService aggregatorService;
    private IrctcEmailParser parser;
    private JobQueue jobQueue;

    @BeforeEach
    void setUp() {
        orderRepo = mock(IrctcOrderRepository.class);
        aggregatorService = mock(AggregatorService.class);
        parser = mock(IrctcEmailParser.class);
        jobQueue = mock(JobQueue.class);

        Aggregator agg = new Aggregator();
        agg.setCode("ZOOP");
        when(aggregatorService.findBySender(any())).thenReturn(Optional.of(agg));
        when(parser.supports(any(), any(), any())).thenReturn(true);
        when(orderRepo.existsBySourceMessageId(any())).thenReturn(false);
        when(orderRepo.existsByAggregatorAndExternalOrderId(any(), any())).thenReturn(false);
        // save() assigns an id so the enqueue payload references it.
        when(orderRepo.save(any(IrctcOrder.class))).thenAnswer(inv -> {
            IrctcOrder o = inv.getArgument(0);
            o.setId(555L);
            return o;
        });
    }

    private IngestionCore core(boolean enrichEnabled) {
        AppMetrics metrics = new AppMetrics(new SimpleMeterRegistry());
        OrderCommandService orderCommandService = mock(OrderCommandService.class);
        IngestFailureRepository failureRepo = mock(IngestFailureRepository.class);
        EnrichmentProperties props =
                new EnrichmentProperties(enrichEnabled, "IRCTC_ECATERING", 1500, "", "", "");
        return new IngestionCore(List.of(parser), orderRepo, aggregatorService, failureRepo, metrics,
                orderCommandService, jobQueue, props,
                mock(com.mayoclone.service.TrainNameService.class),
                mock(com.mayoclone.repository.VendorRepository.class));
    }

    /** A complete parse but with NO passenger name (→ stored as "Unknown"). */
    private static ParsedOrder namelessOrder() {
        return new ParsedOrder("ZOOP", "EXT-9", "1234567890", "12951", "Rajdhani",
                "B3", "32", "BCT", "NDLS", "New Delhi", /* passengerName */ null, "9876543210",
                null, "13:00-13:30", new BigDecimal("360"), "INR", "CONFIRMED",
                List.of(new OrderItem("Veg Biryani", 2, new BigDecimal("180"))),
                "Order", "<mid@x>", "PREPAID", null, null, null, null, null);
    }

    private static RawMessage msg() {
        return new RawMessage("orders@zoopindia.com", "Order", "body", "<mid@x>");
    }

    @Test
    void disabled_doesNotEnqueue() {
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(namelessOrder());

        core(false).process(3L, 4L, MailSourceType.IMAP, msg());

        verify(jobQueue, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void enabled_enqueuesForNamelessOrderWithVendor() {
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(namelessOrder());

        core(true).process(3L, 4L, MailSourceType.IMAP, msg());

        verify(jobQueue).enqueue(eq(DashboardEnrichJobHandler.JOB_TYPE), eq(3L), eq(4L),
                eq("{\"orderId\":555}"), eq("enrich:555"));
    }

    @Test
    void enabled_butNoVendor_doesNotEnqueue() {
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(namelessOrder());

        core(true).process(3L, /* vendorId */ null, MailSourceType.IMAP, msg());

        verify(jobQueue, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void enabled_butOrderHasName_doesNotEnqueue() {
        // passengerName present → not eligible for enrichment.
        ParsedOrder named = new ParsedOrder("ZOOP", "EXT-9", "1234567890", "12951", "Rajdhani",
                "B3", "32", "BCT", "NDLS", "New Delhi", "Rajesh", "9876543210",
                null, "13:00-13:30", new BigDecimal("360"), "INR", "CONFIRMED",
                List.of(new OrderItem("Veg Biryani", 2, new BigDecimal("180"))),
                "Order", "<mid@x>", "PREPAID", null, null, null, null, null);
        when(parser.parse(any(), any(), any(), any(), any())).thenReturn(named);

        core(true).process(3L, 4L, MailSourceType.IMAP, msg());

        verify(jobQueue, never()).enqueue(any(), any(), any(), any(), any());
    }
}
