package com.mayoclone.service;

import com.mayoclone.billing.BillingService;
import com.mayoclone.domain.Account;
import com.mayoclone.domain.SubscriptionStatus;
import com.mayoclone.repository.AccountRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Drives the renewal-reminder sweep directly (scheduler is disabled in the test
 * profile) on H2. A {@link SpyBean} on {@link RenewalReminderSender} lets us assert
 * the email was (or wasn't) composed for a given account.
 */
class RenewalReminderIntegrationTest extends AbstractIntegrationTest {

    @SpyBean
    RenewalReminderSender sender;

    @Autowired
    AccountRepository accountRepo;

    @Autowired
    RenewalReminderJob reminderJob;

    @Autowired
    BillingService billingService;

    @Test
    void activeAccountExpiringSoonIsRemindedThenNotAgain() {
        Account a = save(active("pro-monthly", 3)); // lapses in 3 days, marker null

        reminderJob.sweep();

        Account after = accountRepo.findById(a.getId()).orElseThrow();
        assertNotNull(after.getRenewalReminderSentAt(), "marker should be stamped");
        verify(sender, times(1)).send(any(Account.class), anyInt(), any());

        // A second sweep must NOT re-remind (marker is now set).
        reminderJob.sweep();
        verify(sender, times(1)).send(any(Account.class), anyInt(), any());
    }

    @Test
    void accountExpiringFarOutIsNotReminded() {
        Account a = save(active("pro-monthly", 20)); // outside the 5-day window

        reminderJob.sweep();

        Account after = accountRepo.findById(a.getId()).orElseThrow();
        assertNull(after.getRenewalReminderSentAt(), "far-out account must not be marked");
        verify(sender, never()).send(any(Account.class), anyInt(), any());
    }

    @Test
    void trialingAccountNearTrialEndIsReminded() {
        Account a = new Account();
        a.setBusinessName("Trial Biz");
        a.setEmail(uniqueEmail());
        a.setPasswordHash("x");
        a.setCreatedAt(Instant.now());
        a.setSubscriptionStatus(SubscriptionStatus.TRIALING);
        a.setTrialEndsAt(Instant.now().plus(4, ChronoUnit.DAYS));
        a = save(a);

        reminderJob.sweep();

        Account after = accountRepo.findById(a.getId()).orElseThrow();
        assertNotNull(after.getRenewalReminderSentAt(), "trialing account should be reminded");
        verify(sender, times(1)).send(any(Account.class), anyInt(), any());
    }

    @Test
    void activationResetsTheReminderMarker() {
        Account a = active("pro-monthly", 3);
        a.setRenewalReminderSentAt(Instant.now().minus(1, ChronoUnit.DAYS)); // already reminded
        a = save(a);

        billingService.devActivate(a.getId(), "pro-monthly");

        Account after = accountRepo.findById(a.getId()).orElseThrow();
        assertNull(after.getRenewalReminderSentAt(), "activation must reset the marker to null");
    }

    // --- helpers --------------------------------------------------------------

    private Account active(String plan, int daysToExpiry) {
        Account a = new Account();
        a.setBusinessName("Active Biz");
        a.setEmail(uniqueEmail());
        a.setPasswordHash("x");
        a.setCreatedAt(Instant.now());
        a.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        a.setCurrentPeriodEnd(Instant.now().plus(daysToExpiry, ChronoUnit.DAYS));
        a.setPlan(plan);
        return a;
    }

    private Account save(Account a) {
        return accountRepo.save(a);
    }
}
