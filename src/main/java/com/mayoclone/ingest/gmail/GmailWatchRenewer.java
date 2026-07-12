package com.mayoclone.ingest.gmail;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.repository.ProcessedPushRepository;
import com.mayoclone.repository.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DatabaseMetaData;
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

    /**
     * Distinct advisory-lock key for the watch-renewal sweep (leader election across
     * instances). Different from the poll dispatcher / reminder keys.
     */
    static final long ADVISORY_LOCK_KEY = 748_2003L;

    private final GmailPushProperties props;
    private final GmailWatchService watchService;
    private final VendorRepository vendorRepo;
    private final ProcessedPushRepository processedRepo;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    /** Resolved once: true when the datasource is Postgres (supports advisory locks). */
    private volatile Boolean postgres;

    /** Spring wiring: includes the {@link JdbcTemplate} for the leader lock. */
    @Autowired
    public GmailWatchRenewer(GmailPushProperties props,
                             GmailWatchService watchService,
                             VendorRepository vendorRepo,
                             ProcessedPushRepository processedRepo,
                             JdbcTemplate jdbc) {
        this.props = props;
        this.watchService = watchService;
        this.vendorRepo = vendorRepo;
        this.processedRepo = processedRepo;
        this.jdbc = jdbc;
        this.txTemplate = (jdbc != null && jdbc.getDataSource() != null)
                ? new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                : null;
    }

    /** Test/convenience constructor: no leader lock (single-instance semantics). */
    public GmailWatchRenewer(GmailPushProperties props,
                             GmailWatchService watchService,
                             VendorRepository vendorRepo,
                             ProcessedPushRepository processedRepo) {
        this(props, watchService, vendorRepo, processedRepo, null);
    }

    @Scheduled(fixedDelayString = "${mayoclone.gmail.watch.renew-ms:21600000}")
    public void renew() {
        if (!props.pushConfigured()) {
            return; // no topic → watch disabled, mailboxes are polled
        }
        try {
            if (txTemplate != null && isPostgres()) {
                // Leader election across instances: renew only if this instance wins
                // pg_try_advisory_xact_lock, so a k8s HPA (2-6 pods) doesn't double-renew
                // watches. The lock auto-releases when the tx commits.
                txTemplate.execute(status -> {
                    if (tryLock()) {
                        doSweep();
                    }
                    return null;
                });
            } else {
                doSweep(); // H2/tests/no-jdbc: no cross-instance contention
            }
        } catch (RuntimeException e) {
            log.warn("Gmail watch renew sweep failed: {}", e.getMessage());
        }
    }

    private void doSweep() {
        int renewed = renewExpiring();
        purgeProcessedPush();
        if (renewed > 0) {
            log.info("Gmail watch renewer: refreshed {} mailbox watch(es)", renewed);
        }
    }

    private boolean tryLock() {
        Boolean locked = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        return Boolean.TRUE.equals(locked);
    }

    private boolean isPostgres() {
        Boolean pg = this.postgres;
        if (pg == null) {
            pg = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) conn -> {
                DatabaseMetaData md = conn.getMetaData();
                String name = md.getDatabaseProductName();
                return name != null && name.toLowerCase().contains("postgres");
            }));
            this.postgres = pg;
        }
        return pg;
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
