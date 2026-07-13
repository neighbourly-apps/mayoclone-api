package com.mayoclone.jobs;

import com.mayoclone.domain.DashboardCredentialStatus;
import com.mayoclone.domain.IngestJob;
import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.Vendor;
import com.mayoclone.domain.VendorDashboardCredential;
import com.mayoclone.enrich.DashboardScraper;
import com.mayoclone.enrich.DashboardScraperRegistry;
import com.mayoclone.enrich.EnrichResult;
import com.mayoclone.enrich.EnrichmentProperties;
import com.mayoclone.enrich.OrderEnrichment;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.repository.VendorDashboardCredentialRepository;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.service.OrderCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@code DASHBOARD_ENRICH} handler against mocked collaborators (no DB).
 * Covers the disabled no-op, missing-credential skip, the FOUND fill + review-clear +
 * realtime push, SESSION_EXPIRED → NEEDS_REAUTH, and the retryable throw on RATE_LIMITED.
 */
class DashboardEnrichJobHandlerTest {

    private static final long ACCOUNT = 7L;
    private static final long VENDOR = 4L;
    private static final long ORDER = 99L;

    private IrctcOrderRepository orderRepo;
    private VendorRepository vendorRepo;
    private VendorDashboardCredentialRepository credRepo;
    private OrderCommandService orderCommandService;

    /** Configurable stub scraper. */
    private EnrichResult stubResult;

    @BeforeEach
    void setup() {
        orderRepo = mock(IrctcOrderRepository.class);
        vendorRepo = mock(VendorRepository.class);
        credRepo = mock(VendorDashboardCredentialRepository.class);
        orderCommandService = mock(OrderCommandService.class);
    }

    private DashboardEnrichJobHandler handler(boolean enabled) {
        EnrichmentProperties props =
                new EnrichmentProperties(enabled, "IRCTC_ECATERING", 1500, "", "", "");
        DashboardScraper scraper = new DashboardScraper() {
            @Override
            public String provider() {
                return "IRCTC_ECATERING";
            }

            @Override
            public EnrichResult fetch(Vendor v, VendorDashboardCredential c, String externalOrderId) {
                return stubResult;
            }
        };
        DashboardScraperRegistry registry = new DashboardScraperRegistry(List.of(scraper));
        return new DashboardEnrichJobHandler(props, orderRepo, vendorRepo, credRepo, registry,
                orderCommandService);
    }

    private static IngestJob job() {
        IngestJob j = new IngestJob();
        j.setAccountId(ACCOUNT);
        j.setVendorId(VENDOR);
        j.setPayload("{\"orderId\":" + ORDER + "}");
        return j;
    }

    private static IrctcOrder unknownNameOrder() {
        IrctcOrder o = new IrctcOrder();
        o.setId(ORDER);
        o.setAccountId(ACCOUNT);
        o.setVendorId(VENDOR);
        o.setExternalOrderId("EXT-123");
        o.setPassengerName("Unknown");
        return o;
    }

    private static Vendor activeVendor() {
        Vendor v = new Vendor();
        v.setId(VENDOR);
        v.setAccountId(ACCOUNT);
        v.setActive(true);
        return v;
    }

    private static VendorDashboardCredential cred(DashboardCredentialStatus status) {
        VendorDashboardCredential c = new VendorDashboardCredential();
        c.setAccountId(ACCOUNT);
        c.setVendorId(VENDOR);
        c.setProvider("IRCTC_ECATERING");
        c.setStatus(status);
        return c;
    }

    @Test
    void jobType_isDashboardEnrich() {
        assertEquals("DASHBOARD_ENRICH", handler(true).jobType());
    }

    @Test
    void disabled_isCleanNoOp() {
        handler(false).handle(job());
        verify(orderRepo, never()).findByIdAndAccountId(any(), any());
    }

    @Test
    void missingCredential_isSkipped() {
        when(orderRepo.findByIdAndAccountId(ORDER, ACCOUNT)).thenReturn(Optional.of(unknownNameOrder()));
        when(credRepo.findByVendorIdAndAccountId(VENDOR, ACCOUNT)).thenReturn(Optional.empty());

        handler(true).handle(job());

        verify(orderRepo, never()).save(any());
        verify(orderCommandService, never()).pushEnriched(any());
    }

    @Test
    void orderAlreadyNamed_isNoOp() {
        IrctcOrder named = unknownNameOrder();
        named.setPassengerName("Priya Sharma");
        when(orderRepo.findByIdAndAccountId(ORDER, ACCOUNT)).thenReturn(Optional.of(named));

        handler(true).handle(job());

        verify(credRepo, never()).findByVendorIdAndAccountId(any(), any());
        verify(orderRepo, never()).save(any());
    }

    @Test
    void found_fillsNameClearsReviewAndPushes() {
        IrctcOrder order = unknownNameOrder();
        order.setNeedsReview(true);
        order.setReviewReason("passenger name missing");
        VendorDashboardCredential c = cred(DashboardCredentialStatus.NEW);
        c.setLastError("stale");
        when(orderRepo.findByIdAndAccountId(ORDER, ACCOUNT)).thenReturn(Optional.of(order));
        when(credRepo.findByVendorIdAndAccountId(VENDOR, ACCOUNT)).thenReturn(Optional.of(c));
        when(vendorRepo.findById(VENDOR)).thenReturn(Optional.of(activeVendor()));
        stubResult = EnrichResult.found(OrderEnrichment.ofName("Rajesh Kumar"));

        handler(true).handle(job());

        assertEquals("Rajesh Kumar", order.getPassengerName());
        assertFalse(order.isNeedsReview(), "name-only review reason must clear");
        assertNull(order.getReviewReason());
        assertEquals(DashboardCredentialStatus.ACTIVE, c.getStatus());
        assertNull(c.getLastError());
        verify(orderRepo).save(order);
        verify(orderCommandService).pushEnriched(order);
    }

    @Test
    void found_preservesOtherReviewReasons() {
        IrctcOrder order = unknownNameOrder();
        order.setNeedsReview(true);
        order.setReviewReason("no items parsed; passenger name missing");
        when(orderRepo.findByIdAndAccountId(ORDER, ACCOUNT)).thenReturn(Optional.of(order));
        when(credRepo.findByVendorIdAndAccountId(VENDOR, ACCOUNT))
                .thenReturn(Optional.of(cred(DashboardCredentialStatus.ACTIVE)));
        when(vendorRepo.findById(VENDOR)).thenReturn(Optional.of(activeVendor()));
        stubResult = EnrichResult.found(OrderEnrichment.ofName("Rajesh Kumar"));

        handler(true).handle(job());

        assertTrue(order.isNeedsReview(), "a non-name reason must keep the order flagged");
        assertEquals("no items parsed", order.getReviewReason());
    }

    @Test
    void sessionExpired_setsNeedsReauth_andDoesNotTouchOrder() {
        IrctcOrder order = unknownNameOrder();
        VendorDashboardCredential c = cred(DashboardCredentialStatus.ACTIVE);
        when(orderRepo.findByIdAndAccountId(ORDER, ACCOUNT)).thenReturn(Optional.of(order));
        when(credRepo.findByVendorIdAndAccountId(VENDOR, ACCOUNT)).thenReturn(Optional.of(c));
        when(vendorRepo.findById(VENDOR)).thenReturn(Optional.of(activeVendor()));
        stubResult = EnrichResult.sessionExpired();

        handler(true).handle(job());

        assertEquals(DashboardCredentialStatus.NEEDS_REAUTH, c.getStatus());
        assertTrue(c.getLastError() != null && !c.getLastError().isBlank());
        verify(credRepo).save(c);
        verify(orderRepo, never()).save(any());
        verify(orderCommandService, never()).pushEnriched(any());
    }

    @Test
    void needsReauthCredential_isSkipped() {
        when(orderRepo.findByIdAndAccountId(ORDER, ACCOUNT)).thenReturn(Optional.of(unknownNameOrder()));
        when(credRepo.findByVendorIdAndAccountId(VENDOR, ACCOUNT))
                .thenReturn(Optional.of(cred(DashboardCredentialStatus.NEEDS_REAUTH)));

        handler(true).handle(job());

        verify(vendorRepo, never()).findById(any());
        verify(orderRepo, never()).save(any());
    }

    @Test
    void rateLimited_throwsForQueueRetry() {
        when(orderRepo.findByIdAndAccountId(ORDER, ACCOUNT)).thenReturn(Optional.of(unknownNameOrder()));
        when(credRepo.findByVendorIdAndAccountId(VENDOR, ACCOUNT))
                .thenReturn(Optional.of(cred(DashboardCredentialStatus.ACTIVE)));
        when(vendorRepo.findById(VENDOR)).thenReturn(Optional.of(activeVendor()));
        stubResult = EnrichResult.rateLimited();

        assertThrows(RuntimeException.class, () -> handler(true).handle(job()));
        verify(orderRepo, never()).save(any());
    }
}
