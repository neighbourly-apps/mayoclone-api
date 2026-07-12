package com.mayoclone.service;

import com.mayoclone.domain.Vendor;
import com.mayoclone.ingest.gmail.GmailPushProperties;
import com.mayoclone.jobs.JobQueue;
import com.mayoclone.jobs.MailboxSyncJobHandler;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.VendorRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.List;

/**
 * Replaces the old inline {@code PollScheduler}. Instead of every instance fetching
 * EVERY active mailbox sequentially each cycle (which cannot finish 2000 IMAP fetches
 * in 60s and duplicates work across instances), this dispatcher only ENQUEUES one
 * durable {@code MAILBOX_SYNC} job per due vendor; the shared worker pool
 * ({@link MailboxSyncJobHandler}) does the actual fetching in parallel.
 *
 * <p><b>Single-dispatcher guard (multi-instance).</b> Each tick tries a Postgres
 * advisory lock so only one instance enqueues per tick. This is a redundant-work
 * optimisation, NOT a correctness requirement — the queue's {@code dedupKey} coalescing
 * ({@code mbsync:<vendorId>}) already prevents duplicate jobs even if two instances
 * dispatch at once. On H2/non-Postgres the lock is skipped so unit tests run.
 *
 * <p><b>Capacity.</b> {@code dispatch-batch-size} vendors are enqueued every
 * {@code dispatch-tick-ms}. batch-size × ticks/minute must exceed vendors ÷
 * (interval in minutes). Default 200 every 5s = 40/s = 2400/min, which drains 2000
 * mailboxes well within a 60s per-vendor interval. Each dispatched vendor's
 * {@code next_poll_at} is bumped forward immediately so it is not re-selected while its
 * job is queued/running; the handler resets it on completion.
 */
@Component
public class MailboxPollDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailboxPollDispatcher.class);

    /** Constant advisory-lock key shared by all instances (arbitrary, app-unique). */
    static final long ADVISORY_LOCK_KEY = 748_2001L;

    private final VendorRepository vendorRepo;
    private final JobQueue jobQueue;
    private final GmailPushProperties pushProps;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final AppMetrics metrics;

    private final boolean enabled;
    private final long intervalMs;
    private final int batchSize;

    /** Resolved once: true when the datasource is Postgres (supports advisory locks). */
    private volatile Boolean postgres;

    public MailboxPollDispatcher(VendorRepository vendorRepo,
                                 JobQueue jobQueue,
                                 GmailPushProperties pushProps,
                                 JdbcTemplate jdbc,
                                 AppMetrics metrics,
                                 MeterRegistry registry,
                                 @Value("${mayoclone.poll.enabled:false}") boolean enabled,
                                 @Value("${mayoclone.poll.interval-ms:60000}") long intervalMs,
                                 @Value("${mayoclone.poll.dispatch-batch-size:200}") int batchSize) {
        this.vendorRepo = vendorRepo;
        this.jobQueue = jobQueue;
        this.pushProps = pushProps;
        this.jdbc = jdbc;
        this.txTemplate = (jdbc != null && jdbc.getDataSource() != null)
                ? new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                : null;
        this.metrics = metrics;
        this.enabled = enabled;
        this.intervalMs = intervalMs;
        this.batchSize = batchSize;

        // Cheap backlog gauge: number of due, pollable vendors right now.
        Gauge.builder("mayoclone.poll.due.vendors", this, MailboxPollDispatcher::dueBacklog)
                .description("Vendors currently due for a mailbox sync")
                .register(registry);
    }

    // ------------------------------------------------------------------ schedule

    @Scheduled(fixedDelayString = "${mayoclone.poll.dispatch-tick-ms:5000}")
    public void tick() {
        if (!enabled) {
            return; // polling disabled — do nothing
        }
        try {
            if (txTemplate != null && isPostgres()) {
                // Single-dispatcher guard: hold a TRANSACTION-scoped advisory lock for the
                // whole enqueue, auto-released on commit. (The previous session-scoped
                // pg_try_advisory_lock was acquired on one pooled connection and released
                // on another — the lock leaked and was never freed.) This is a
                // redundant-work optimisation only; the dedupKey guarantees correctness.
                txTemplate.execute(status -> tryXactLock() ? dispatchDue() : 0);
            } else {
                dispatchDue(); // H2/non-Postgres: no cross-instance contention in tests
            }
        } catch (RuntimeException e) {
            log.warn("Mailbox poll dispatch failed: {}", e.toString());
        }
    }

    /**
     * Select the due, pollable vendors and enqueue one MAILBOX_SYNC per vendor. Bumps
     * each vendor's {@code next_poll_at} immediately so it is not re-selected until its
     * job completes. Package-visible so tests can drive one dispatch pass directly.
     */
    int dispatchDue() {
        Instant now = Instant.now();
        boolean gmailPollable = gmailPollable();
        List<Vendor> due = vendorRepo.findDuePollable(now, gmailPollable, PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return 0;
        }
        Instant next = now.plusMillis(intervalMs);
        for (Vendor v : due) {
            jobQueue.enqueue(MailboxSyncJobHandler.JOB_TYPE, v.getAccountId(), v.getId(),
                    "{}", "mbsync:" + v.getId());
            v.setNextPollAt(next);
            vendorRepo.save(v);
        }
        metrics.pollDispatched(due.size());
        log.debug("Mailbox poll dispatch enqueued {} MAILBOX_SYNC job(s) (gmailPollable={})",
                due.size(), gmailPollable);
        return due.size();
    }

    /**
     * Gmail vendors are polled ONLY when push is not handling them: false when a Pub/Sub
     * topic is configured AND {@code skip-gmail-when-push} is on.
     */
    boolean gmailPollable() {
        return !(pushProps.pushConfigured() && pushProps.skipGmailWhenPush());
    }

    private double dueBacklog() {
        try {
            return vendorRepo.countDuePollable(Instant.now(), gmailPollable());
        } catch (RuntimeException e) {
            return 0d;
        }
    }

    // ---------------------------------------------------------- advisory lock

    /**
     * Transaction-scoped advisory lock: acquired on the transaction's connection and
     * auto-released when that transaction commits/rolls back — no manual unlock, no leak.
     * Must be called from within the {@link #txTemplate} transaction.
     */
    private boolean tryXactLock() {
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
}
