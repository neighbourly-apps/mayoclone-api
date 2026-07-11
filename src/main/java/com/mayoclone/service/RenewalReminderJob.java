package com.mayoclone.service;

import com.mayoclone.billing.BillingProperties;
import com.mayoclone.domain.Account;
import com.mayoclone.domain.SubscriptionStatus;
import com.mayoclone.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    private final AccountRepository accountRepo;
    private final RenewalReminderSender sender;
    private final BillingProperties billingProps;

    private final boolean enabled;
    private final int daysBefore;
    private final int maxPerRun;

    public RenewalReminderJob(AccountRepository accountRepo,
                              RenewalReminderSender sender,
                              BillingProperties billingProps,
                              @Value("${mayoclone.billing.reminder.enabled:true}") boolean enabled,
                              @Value("${mayoclone.billing.reminder.days-before:5}") int daysBefore,
                              @Value("${mayoclone.billing.reminder.max-per-run:500}") int maxPerRun) {
        this.accountRepo = accountRepo;
        this.sender = sender;
        this.billingProps = billingProps;
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
            int sent = sweep();
            if (sent > 0) {
                log.info("Renewal reminder sweep sent {} reminder(s)", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Renewal reminder sweep failed: {}", e.toString());
        }
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
