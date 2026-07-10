package com.mayoclone.service;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.ingest.IngestionCore;
import com.mayoclone.ingest.MailSource;
import com.mayoclone.ingest.MailSourceRegistry;
import com.mayoclone.ingest.RawMessage;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.VendorRepository;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Orchestrates vendor mailbox syncs. It no longer talks IMAP directly: it picks
 * the right {@link MailSource} for a vendor's {@link MailSourceType} (via
 * {@link MailSourceRegistry}), fetches, and hands each {@link RawMessage} to the
 * shared {@link IngestionCore}. FORWARDING vendors are push-only and are skipped
 * here (their mail arrives via the inbound webhook).
 *
 * <p>{@link #ingestRaw} keeps its original signature so the demo endpoint and the
 * webhook can feed the pipeline without a mailbox.
 */
@Service
public class ImapIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ImapIngestionService.class);

    private final MailSourceRegistry mailSources;
    private final IngestionCore ingestionCore;
    private final VendorRepository vendorRepo;
    private final AppMetrics metrics;

    public ImapIngestionService(MailSourceRegistry mailSources,
                                IngestionCore ingestionCore,
                                VendorRepository vendorRepo,
                                AppMetrics metrics) {
        this.mailSources = mailSources;
        this.ingestionCore = ingestionCore;
        this.vendorRepo = vendorRepo;
        this.metrics = metrics;
    }

    /** Sync a single vendor mailbox: fetch new messages via its MailSource, process each. */
    public IngestResult syncVendor(Vendor vendor) {
        MailSource source = mailSources.forVendor(vendor).orElse(null);
        if (source == null) {
            // FORWARDING (push-only) or an unknown/unsupported type — nothing to pull.
            log.debug("No pull MailSource for vendor {} (sourceType={}) — skipping",
                    vendor.getRestaurantName(), vendor.getSourceType());
            return IngestResult.empty();
        }

        Timer.Sample sample = metrics.startSyncTimer();
        try {
            List<RawMessage> messages = source.fetch(vendor);
            int fetched = 0;
            int newOrders = 0;
            for (RawMessage msg : messages) {
                fetched++;
                if (ingestionCore.process(vendor.getAccountId(), vendor.getId(),
                        vendor.getSourceType(), msg).newOrders() > 0) {
                    newOrders++;
                }
            }
            vendor.setLastSyncedAt(Instant.now());
            vendorRepo.save(vendor);
            return new IngestResult(fetched, newOrders);
        } finally {
            metrics.stopSyncTimer(sample, vendor.getSourceType().name());
        }
    }

    /**
     * Sync every active vendor across ALL tenants, inline. Scheduled polling now runs
     * per-vendor on the durable job queue ({@link MailboxPollDispatcher} +
     * {@code MAILBOX_SYNC} handler); this inline sweep is retained only for manual/admin
     * use. Request-driven syncs must use {@link #syncAccountVendors(Long)}.
     */
    public IngestResult syncAllActive() {
        return syncAllActive(false);
    }

    /**
     * As {@link #syncAllActive()} but, when {@code skipGmailOAuth} is true, GMAIL_OAUTH
     * vendors are excluded — they are driven by Gmail Pub/Sub push instead of polling.
     * IMAP/other vendors are always polled. Both paths dedup, so correctness holds
     * regardless of which the vendor takes.
     */
    public IngestResult syncAllActive(boolean skipGmailOAuth) {
        List<Vendor> vendors = vendorRepo.findByActiveTrue();
        if (skipGmailOAuth) {
            vendors = vendors.stream()
                    .filter(v -> v.getSourceType() != MailSourceType.GMAIL_OAUTH)
                    .toList();
        }
        return syncAll(vendors);
    }

    /** Sync only the given tenant's active vendors (request-driven /api/sync). */
    public IngestResult syncAccountVendors(Long accountId) {
        return syncAll(vendorRepo.findByAccountIdAndActiveTrue(accountId));
    }

    private IngestResult syncAll(List<Vendor> vendors) {
        IngestResult total = IngestResult.empty();
        for (Vendor vendor : vendors) {
            try {
                total = total.plus(syncVendor(vendor));
            } catch (RuntimeException e) {
                // One bad mailbox shouldn't abort the whole sweep.
                log.warn("Skipping vendor {}: {}", vendor.getRestaurantName(), e.getMessage());
            }
        }
        return total;
    }

    /**
     * Run the route → parse → dedup → store path for a single raw email, bypassing
     * any mailbox. Returns fetched=1 always; newOrders=1 if a fresh order was
     * stored, else 0. {@code accountId} (the owning tenant) is required;
     * {@code vendorId} may be null (e.g. demo orders).
     */
    public IngestResult ingestRaw(String from, String subject, String body, String messageId,
                                  Long accountId, Long vendorId) {
        return ingestionCore.process(accountId, vendorId, MailSourceType.IMAP,
                new RawMessage(from, subject, body, messageId));
    }
}
