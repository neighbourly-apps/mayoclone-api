package com.mayoclone.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link JobHandler} for {@code DASHBOARD_ENRICH}: back-fills one order's missing
 * fields (chiefly the passenger name, which IRCTC eCatering emails omit) by looking it
 * up on the aggregator's vendor dashboard via a {@link DashboardScraper}.
 *
 * <p><b>Off by default, safe by construction.</b> Enqueued only when
 * {@code mayoclone.enrich.enabled=true} (see {@code IngestionCore}); the handler ALSO
 * re-checks the flag so a stale job is a no-op. Guards short-circuit to a clean DONE
 * for: feature off, order gone / already named, credential missing/DISABLED/NEEDS_REAUTH,
 * inactive vendor, or no scraper for the provider. Everything is tenant-scoped on the
 * job's {@code accountId}.
 *
 * <p><b>Outcome handling.</b> FOUND → fill ONLY blank fields (never clobber a good
 * parse), recompute the review verdict, mark the credential ACTIVE, save, push a
 * realtime ENRICHED event. NOT_FOUND → leave as-is, DONE (don't hammer). SESSION_EXPIRED
 * → mark the credential NEEDS_REAUTH, DONE. RATE_LIMITED / ENGINE_ERROR → THROW so the
 * durable queue retries with exponential backoff.
 */
@Component
public class DashboardEnrichJobHandler implements JobHandler {

    public static final String JOB_TYPE = "DASHBOARD_ENRICH";

    private static final Logger log = LoggerFactory.getLogger(DashboardEnrichJobHandler.class);
    private static final int MAX_ERROR_LEN = 500;
    /** A parsed-but-empty name the email pipeline stamps when it found no passenger. */
    private static final String UNKNOWN = "Unknown";

    private final EnrichmentProperties props;
    private final IrctcOrderRepository orderRepo;
    private final VendorRepository vendorRepo;
    private final VendorDashboardCredentialRepository credRepo;
    private final DashboardScraperRegistry scrapers;
    private final OrderCommandService orderCommandService;
    private final ObjectMapper mapper = new ObjectMapper();

    public DashboardEnrichJobHandler(EnrichmentProperties props,
                                     IrctcOrderRepository orderRepo,
                                     VendorRepository vendorRepo,
                                     VendorDashboardCredentialRepository credRepo,
                                     DashboardScraperRegistry scrapers,
                                     OrderCommandService orderCommandService) {
        this.props = props;
        this.orderRepo = orderRepo;
        this.vendorRepo = vendorRepo;
        this.credRepo = credRepo;
        this.scrapers = scrapers;
        this.orderCommandService = orderCommandService;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(IngestJob job) {
        // Feature master switch — a stale job enqueued before the flag flipped is a no-op.
        if (!props.isEnabled()) {
            return;
        }
        Long accountId = job.getAccountId();
        Long orderId = parseOrderId(job.getPayload());
        if (accountId == null || orderId == null) {
            return; // malformed job — nothing to do
        }

        // Tenant-scoped order lookup. Gone → idempotent no-op.
        IrctcOrder order = orderRepo.findByIdAndAccountId(orderId, accountId).orElse(null);
        if (order == null) {
            return;
        }
        // Already has a real name (a concurrent enrich or a good parse) → nothing to do.
        if (!isUnknownOrBlank(order.getPassengerName())) {
            return;
        }
        // A synthesized order id (email lacked a real order number → "<AGGCODE>-<digits>") can't
        // be looked up on the dashboard — skip cleanly instead of retrying to dead-letter. Match
        // ONLY this order's own aggregator-code prefix (a real order id is the aggregator's own
        // number, e.g. "2465529423", which never has that prefix).
        String extId = order.getExternalOrderId();
        String aggCode = order.getAggregator() != null ? order.getAggregator().getCode() : null;
        if (extId == null
                || (aggCode != null && extId.matches("^" + java.util.regex.Pattern.quote(aggCode) + "-\\d+$"))) {
            return;
        }
        Long vendorId = order.getVendorId();
        if (vendorId == null) {
            return;
        }

        // Credential must exist and be usable.
        VendorDashboardCredential cred = credRepo.findByVendorIdAndAccountId(vendorId, accountId).orElse(null);
        if (cred == null
                || cred.getStatus() == DashboardCredentialStatus.DISABLED
                || cred.getStatus() == DashboardCredentialStatus.NEEDS_REAUTH) {
            return;
        }

        // Vendor must exist and be active.
        Vendor vendor = vendorRepo.findById(vendorId).orElse(null);
        if (vendor == null || !vendor.isActive() || !accountId.equals(vendor.getAccountId())) {
            return;
        }

        // A scraper must be registered for this provider.
        DashboardScraper scraper = scrapers.forProvider(cred.getProvider()).orElse(null);
        if (scraper == null) {
            log.debug("DASHBOARD_ENRICH: no scraper for provider {} (order {})", cred.getProvider(), orderId);
            return;
        }

        EnrichResult result = scraper.fetch(vendor, cred, order.getExternalOrderId());
        switch (result.outcome()) {
            case FOUND -> applyFound(order, cred, result.enrichment());
            case NOT_FOUND -> {
                // Dashboard had nothing to add — leave the order untouched and stop (DONE).
                // Persist any session the scraper cached so a later job reuses it.
                touchCred(cred, cred.getStatus(), null);
            }
            case SESSION_EXPIRED -> touchCred(cred, DashboardCredentialStatus.NEEDS_REAUTH,
                    "dashboard login rejected — re-enter credentials");
            case RATE_LIMITED -> throw new IllegalStateException(
                    "dashboard rate-limited (429) for order " + orderId + " — will retry");
            case ENGINE_ERROR -> throw new IllegalStateException(
                    "dashboard enrichment engine error for order " + orderId + " — will retry");
        }
    }

    // --------------------------------------------------------------------- FOUND

    private void applyFound(IrctcOrder order, VendorDashboardCredential cred, OrderEnrichment e) {
        boolean nameFilled = false;
        if (e != null) {
            if (isUnknownOrBlank(order.getPassengerName()) && notBlank(e.passengerName())) {
                order.setPassengerName(e.passengerName().trim());
                nameFilled = true;
            }
            if (isBlank(order.getPassengerPhone()) && notBlank(e.passengerPhone())) {
                order.setPassengerPhone(e.passengerPhone().trim());
            }
            if (isBlank(order.getCoach()) && notBlank(e.coach())) {
                order.setCoach(e.coach().trim());
            }
            if (isBlank(order.getBerth()) && notBlank(e.berth())) {
                order.setBerth(e.berth().trim());
            }
            if (isBlank(order.getPnr()) && notBlank(e.pnr())) {
                order.setPnr(e.pnr().trim());
            }
            if (isBlank(order.getDeliverySlot()) && notBlank(e.deliverySlot())) {
                order.setDeliverySlot(e.deliverySlot().trim());
            }
            if (isBlank(order.getTrainNumber()) && notBlank(e.trainNumber())) {
                order.setTrainNumber(e.trainNumber().trim());
            }
            // IRCTC emails omit the train NAME — the dashboard supplies it.
            if (isBlank(order.getTrainName()) && notBlank(e.trainName())) {
                order.setTrainName(e.trainName().trim());
            }
        }

        // Recompute the review verdict: drop any name-related reason now that the name is
        // filled; clear needsReview only if that was the SOLE remaining reason.
        if (nameFilled) {
            clearNameReviewReason(order);
        }

        // Credential succeeded → ACTIVE, clear the last error (and persist any cached session).
        cred.setStatus(DashboardCredentialStatus.ACTIVE);
        cred.setLastError(null);
        cred.setUpdatedAt(Instant.now());
        credRepo.save(cred);

        orderRepo.save(order);
        // Realtime: tell the SPA the order was back-filled.
        orderCommandService.pushEnriched(order);
    }

    /**
     * Remove any review reason that is purely about a missing/unknown passenger name and
     * clear {@code needsReview} when nothing else remains. Other reasons (no items,
     * amount missing, ...) are preserved untouched.
     */
    private static void clearNameReviewReason(IrctcOrder order) {
        String reason = order.getReviewReason();
        if (reason == null || reason.isBlank()) {
            return;
        }
        List<String> kept = new ArrayList<>();
        for (String part : reason.split(";")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (!token.toLowerCase().contains("name")) {
                kept.add(token);
            }
        }
        if (kept.isEmpty()) {
            order.setNeedsReview(false);
            order.setReviewReason(null);
        } else {
            order.setReviewReason(String.join("; ", kept));
        }
    }

    // --------------------------------------------------------------------- cred

    private void touchCred(VendorDashboardCredential cred, DashboardCredentialStatus status, String error) {
        cred.setStatus(status);
        if (error != null) {
            cred.setLastError(truncate(error));
        }
        cred.setUpdatedAt(Instant.now());
        credRepo.save(cred);
    }

    // -------------------------------------------------------------------- helpers

    private Long parseOrderId(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(payload).path("orderId");
            return node.isNumber() || node.isTextual() ? node.asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the name is null/blank or the pipeline's "Unknown" placeholder. */
    public static boolean isUnknownOrBlank(String name) {
        return name == null || name.isBlank() || UNKNOWN.equalsIgnoreCase(name.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }
}
