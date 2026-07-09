package com.mayoclone.ingest.gmail;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.repository.ProcessedPushRepository;
import com.mayoclone.repository.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Periodically re-registers Gmail {@code users.watch} for active GMAIL_OAUTH
 * mailboxes whose watch is expired, null, or lapsing within 24h (Gmail expires a
 * watch after ~7 days). Also purges old {@code processed_push} idempotency rows.
 *
 * <p>Gated on a configured Pub/Sub topic: with no topic set the sweep is a clean
 * no-op (mailboxes are polled instead), so it stays inert in builds/tests.
 */
@Component
public class GmailWatchRenewer {

    private static final Logger log = LoggerFactory.getLogger(GmailWatchRenewer.class);

    /** Renew a watch this far ahead of its expiry. */
    private static final Duration RENEW_WINDOW = Duration.ofHours(24);
    /** Retention for the push idempotency ledger. */
    private static final Duration PUSH_RETENTION = Duration.ofDays(7);

    private final GmailPushProperties props;
    private final GmailWatchService watchService;
    private final VendorRepository vendorRepo;
    private final ProcessedPushRepository processedRepo;

    public GmailWatchRenewer(GmailPushProperties props,
                             GmailWatchService watchService,
                             VendorRepository vendorRepo,
                             ProcessedPushRepository processedRepo) {
        this.props = props;
        this.watchService = watchService;
        this.vendorRepo = vendorRepo;
        this.processedRepo = processedRepo;
    }

    @Scheduled(fixedDelayString = "${mayoclone.gmail.watch.renew-ms:21600000}")
    public void renew() {
        if (!props.pushConfigured()) {
            return; // no topic → watch disabled, mailboxes are polled
        }
        try {
            int renewed = renewExpiring();
            purgeProcessedPush();
            if (renewed > 0) {
                log.info("Gmail watch renewer: refreshed {} mailbox watch(es)", renewed);
            }
        } catch (RuntimeException e) {
            log.warn("Gmail watch renew sweep failed: {}", e.getMessage());
        }
    }

    /** Re-watch mailboxes whose watch is null/expiring; returns how many were renewed. Package-visible for tests. */
    int renewExpiring() {
        Instant threshold = Instant.now().plus(RENEW_WINDOW);
        List<Vendor> gmailVendors = vendorRepo.findByActiveTrueAndSourceType(MailSourceType.GMAIL_OAUTH);
        int renewed = 0;
        for (Vendor v : gmailVendors) {
            Instant exp = v.getGmailWatchExpiration();
            if (exp == null || exp.isBefore(threshold)) {
                watchService.startWatch(v, false); // keep existing baseline historyId
                renewed++;
            }
        }
        return renewed;
    }

    private void purgeProcessedPush() {
        try {
            long removed = processedRepo.deleteByReceivedAtBefore(Instant.now().minus(PUSH_RETENTION));
            if (removed > 0) {
                log.debug("Gmail watch renewer: purged {} processed_push row(s)", removed);
            }
        } catch (RuntimeException e) {
            log.debug("processed_push purge skipped: {}", e.getMessage());
        }
    }
}
