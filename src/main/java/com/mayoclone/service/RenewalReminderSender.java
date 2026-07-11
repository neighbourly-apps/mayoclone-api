package com.mayoclone.service;

import com.mayoclone.billing.BillingDtos.PlanDto;
import com.mayoclone.domain.Account;

import java.util.Locale;

/**
 * Delivers a "your subscription renews soon" reminder to an account owner, 5 days
 * (config-driven) before their paid period or free trial lapses. Two implementations
 * exist, mirroring {@link OtpSender}: {@link SmtpRenewalReminderSender} (real email,
 * active only when {@code spring.mail.host} is set) and {@link LoggingRenewalReminderSender}
 * (dev fallback, logs at INFO so it's testable without SMTP).
 *
 * <p>The message is plain, branded, and contains NO secrets: the business name, days
 * remaining, the plan's price, and the billing URL.
 */
public interface RenewalReminderSender {

    String SUBJECT = "Your MayoClone subscription renews soon";

    /** Deliver the reminder. Implementations must not throw for transient dev issues. */
    void send(Account account, int daysLeft, PlanDto plan);

    /** Shared, branded plain-text body. Kept here so both senders compose it identically. */
    static String composeBody(Account account, int daysLeft, PlanDto plan, String billingUrl) {
        String business = account.getBusinessName() == null ? "there" : account.getBusinessName();
        String amount = formatAmount(plan);
        String when = daysLeft <= 1 ? "in less than a day" : "in " + daysLeft + " days";
        return "Hi " + business + ",\n\n"
                + "Your MayoClone subscription (" + plan.name() + " plan, " + amount + ") "
                + "is set to renew " + when + ".\n\n"
                + "To keep your e-catering order dashboard active without interruption, "
                + "renew from your billing page:\n"
                + billingUrl + "\n\n"
                + "Thanks,\nThe MayoClone team";
    }

    /** e.g. "INR 1200.00" — minor units → major, no locale surprises. */
    static String formatAmount(PlanDto plan) {
        return plan.currency() + " " + String.format(Locale.ROOT, "%.2f", plan.amount() / 100.0);
    }
}
