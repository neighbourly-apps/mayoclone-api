package com.mayoclone.service;

import com.mayoclone.billing.BillingProperties;
import com.mayoclone.domain.Account;
import com.mayoclone.domain.SubscriptionStatus;
import com.mayoclone.repository.AccountRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Emails account owners a "renews soon" reminder {@code days-before} (default 5) their
 * paid period or free trial lapses, so they renew (especially monthly). Gated by
 * {@code mayoclone.billing.reminder.enabled} (default true) and scheduled by
 * {@code mayoclone.billing.reminder.fixed-delay-ms} (default hourly).
 *
 * <p>Dedupe is per-period via {@code account.renewal_reminder_sent_at} (V15): the sweep
 * only selects accounts where it is null, and stamps it once reminded; each (re)activation
 * resets it to null. SUPER_ADMIN accounts are excluded. Capped at {@code max-per-run}
 * accounts per sweep and each send is wrapped so one failure can't abort the run.
 */
@Component
public class RenewalReminderJob {

    private static final Logger log = LoggerFactory.getLogger(RenewalReminderJob.class);

    /**
     * Distinct advisory-lock key for the renewal-reminder sweep (leader election across
     * instances). Different from the poll dispatcher / watch renewer keys.
     */
    static final long ADVISORY_LOCK_KEY = 748_2002L;

    private final AccountRepository accountRepo;
    private final RenewalReminderSender sender;
    private final BillingProperties billingProps;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    private final boolean enabled;
    private final int daysBefore;
    private final int maxPerRun;

    /** Resolved once: true when the datasource is Postgres (supports advisory locks). */
    private volatile Boolean postgres;

    public RenewalReminderJob(AccountRepository accountRepo,
                              RenewalReminderSender sender,
                              BillingProperties billingProps,
                              JdbcTemplate jdbc,
                              @Value("${mayoclone.billing.reminder.enabled:true}") boolean enabled,
                              @Value("${mayoclone.billing.reminder.days-before:5}") int daysBefore,
                              @Value("${mayoclone.billing.reminder.max-per-run:500}") int maxPerRun) {
        this.accountRepo = accountRepo;
        this.sender = sender;
        this.billingProps = billingProps;
        this.jdbc = jdbc;
        this.txTemplate = (jdbc != null && jdbc.getDataSource() != null)
                ? new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                : null;
        this.enabled = enabled;
        this.daysBefore = Math.max(1, daysBefore);
        this.maxPerRun = Math.max(1, maxPerRun);
    }

    @Scheduled(fixedDelayString = "${mayoclone.billing.reminder.fixed-delay-ms:3600000}",
            initialDelayString = "${mayoclone.billing.reminder.initial-delay-ms:60000}")
    public void tick() {
        if (!enabled) {
            return; // reminders disabled — do nothing
        }
        try {
            int sent;
            if (txTemplate != null && isPostgres()) {
                // Leader election across instances: run the sweep only if this instance
                // wins pg_try_advisory_xact_lock, so a k8s HPA (2-6 pods) doesn't
                // double-send reminders. The lock auto-releases when the tx commits.
                Integer n = txTemplate.execute(status -> tryLock() ? sweep() : 0);
                sent = n == null ? 0 : n;
            } else {
                sent = sweep(); // H2/tests/no-jdbc: no cross-instance contention
            }
            if (sent > 0) {
                log.info("Renewal reminder sweep sent {} reminder(s)", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Renewal reminder sweep failed: {}", e.toString());
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

    /**
     * One sweep pass. Selects up to {@code max-per-run} tenant accounts lapsing within
     * the window with a null reminder marker, emails each, and stamps the marker.
     * Package-visible so tests can drive a pass deterministically. Returns the count sent.
     *
     * <p>Deliberately NOT wrapped in a single transaction: the initial query reads a
     * capped page, then each account's mark-and-save runs in its own (Spring Data
     * {@code save}) transaction so one bad row can't roll back the rest of the sweep.
     */
    int sweep() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(daysBefore, ChronoUnit.DAYS);
        List<Account> due = accountRepo.findDueForRenewalReminder(
                Account.ROLE_SUPER_ADMIN, SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING,
                now, cutoff, PageRequest.of(0, maxPerRun));
        int sent = 0;
        for (Account a : due) {
            try {
                Instant lapsesAt = a.getSubscriptionStatus() == SubscriptionStatus.ACTIVE
                        ? a.getCurrentPeriodEnd() : a.getTrialEndsAt();
                sender.send(a, daysLeft(now, lapsesAt), billingProps.planOrDefault(a.getPlan()));
                a.setRenewalReminderSentAt(now);
                accountRepo.save(a);
                sent++;
            } catch (RuntimeException e) {
                // One bad send must not abort the sweep; try again next tick (marker stays null).
                log.warn("Renewal reminder to account {} failed: {}", a.getId(), e.toString());
            }
        }
        return sent;
    }

    private static int daysLeft(Instant now, Instant lapsesAt) {
        if (lapsesAt == null) {
            return 0;
        }
        long minutes = Duration.between(now, lapsesAt).toMinutes();
        return (int) Math.max(0, (long) Math.ceil(minutes / (60.0 * 24.0)));
    }
}
