package com.mayoclone.jobs;

import com.mayoclone.domain.IngestJob;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.service.ImapIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link JobHandler} for {@code MAILBOX_SYNC}: syncs ONE vendor mailbox off the durable
 * job queue. Enqueued (coalesced on {@code mbsync:<vendorId>}) by
 * {@link com.mayoclone.service.MailboxPollDispatcher}. This is what makes polling scale
 * to 2000+ restaurants: instead of one instance fetching every mailbox inline each
 * cycle, each mailbox is an independent unit of work drained by the shared worker pool.
 *
 * <p><b>Backoff model.</b> Retry cadence is governed at the VENDOR level via
 * {@code next_poll_at}, NOT by the queue. On an expected mailbox error we record the
 * failure, push {@code next_poll_at} out with per-vendor exponential backoff, and
 * return normally (job → DONE) so a single broken mailbox is not retried every tick and
 * we avoid double-backoff (queue backoff stacked on vendor backoff). Only truly
 * unexpected/infra errors (e.g. the database is down) rethrow so the queue retries.
 *
 * <p>Idempotent: {@link com.mayoclone.ingest.IngestionCore} dedups on Message-ID, so a
 * duplicated delivery is harmless.
 */
@Component
public class MailboxSyncJobHandler implements JobHandler {

    public static final String JOB_TYPE = "MAILBOX_SYNC";

    private static final Logger log = LoggerFactory.getLogger(MailboxSyncJobHandler.class);
    private static final int MAX_ERROR_LEN = 500;
    /** Cap the exponent so {@code interval << shift} never overflows and backoff stays bounded. */
    private static final int MAX_BACKOFF_SHIFT = 6;

    private final VendorRepository vendorRepo;
    private final ImapIngestionService ingestionService;
    private final AppMetrics metrics;

    private final long intervalMs;
    private final long backoffCapMs;

    public MailboxSyncJobHandler(VendorRepository vendorRepo,
                                 ImapIngestionService ingestionService,
                                 AppMetrics metrics,
                                 @Value("${mayoclone.poll.interval-ms:60000}") long intervalMs,
                                 @Value("${mayoclone.poll.backoff-cap-ms:3600000}") long backoffCapMs) {
        this.vendorRepo = vendorRepo;
        this.ingestionService = ingestionService;
        this.metrics = metrics;
        this.intervalMs = intervalMs;
        this.backoffCapMs = backoffCapMs;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(IngestJob job) {
        Long vendorId = job.getVendorId();
        if (vendorId == null) {
            return; // malformed job — nothing to do
        }
        Vendor vendor = vendorRepo.findById(vendorId).orElse(null);
        if (vendor == null || !vendor.isActive() || vendor.getSourceType() == MailSourceType.FORWARDING) {
            // Missing / deactivated / push-only (FORWARDING) — clean no-op, job completes.
            log.debug("MAILBOX_SYNC skip: vendor {} missing/inactive/forwarding", vendorId);
            return;
        }

        Instant now = Instant.now();
        try {
            ingestionService.syncVendor(vendor);
            // Success: clear failure state and schedule the next regular poll.
            vendor.setPollFailureCount(0);
            vendor.setLastSyncError(null);
            vendor.setNextPollAt(now.plusMillis(intervalMs));
            vendorRepo.save(vendor);
            metrics.mailboxSync(AppMetrics.RESULT_OK);
        } catch (DataAccessException infra) {
            // Genuine infra failure (DB down, etc.) — let the queue retry with its own backoff.
            throw infra;
        } catch (RuntimeException mailbox) {
            // Expected mailbox error (IMAP auth/connect, Gmail token, parse, ...). Record it and
            // back off at the VENDOR level; DO NOT rethrow — the job completes DONE.
            int failures = vendor.getPollFailureCount() + 1;
            vendor.setPollFailureCount(failures);
            vendor.setLastSyncError(truncate(mailbox.getMessage()));
            vendor.setNextPollAt(now.plusMillis(backoffWithJitter(failures)));
            vendorRepo.save(vendor);
            metrics.mailboxSync(AppMetrics.RESULT_FAIL);
            log.warn("MAILBOX_SYNC failed for vendor {} (failures={}): {}",
                    vendorId, failures, mailbox.toString());
        }
    }

    /**
     * Per-vendor exponential backoff (no jitter): {@code min(interval * 2^min(failures,6), cap)}.
     * Monotonic non-decreasing in {@code failures} and capped at {@code backoff-cap-ms}.
     * Package-visible so the unit test can assert the schedule deterministically.
     */
    long backoffMs(int failures) {
        int shift = Math.min(Math.max(failures, 0), MAX_BACKOFF_SHIFT);
        long exp = intervalMs << shift;
        if (exp <= 0 || exp > backoffCapMs) {
            return backoffCapMs;
        }
        return exp;
    }

    /** {@link #backoffMs} plus up-to-10% jitter so many broken mailboxes don't retry in lockstep. */
    private long backoffWithJitter(int failures) {
        long base = backoffMs(failures);
        long jitter = base <= 0 ? 0 : ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 10));
        return base + jitter;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }
}
