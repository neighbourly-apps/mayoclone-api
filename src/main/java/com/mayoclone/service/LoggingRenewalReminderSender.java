package com.mayoclone.service;

import com.mayoclone.billing.BillingDtos.PlanDto;
import com.mayoclone.domain.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev/no-SMTP fallback {@link RenewalReminderSender}: logs the composed reminder at
 * INFO so it's fully testable without SMTP. Active whenever {@code spring.mail.host}
 * is NOT configured; {@link SmtpRenewalReminderSender} takes over when it is.
 */
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host", havingValue = "false", matchIfMissing = true)
public class LoggingRenewalReminderSender implements RenewalReminderSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingRenewalReminderSender.class);

    private final String billingUrl;

    public LoggingRenewalReminderSender(
            @Value("${mayoclone.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.billingUrl = frontendBaseUrl.replaceAll("/+$", "") + "/billing";
    }

    @Override
    public void send(Account account, int daysLeft, PlanDto plan) {
        log.info("[DEV RENEWAL] To {}: \"{}\" — {}", account.getEmail(), SUBJECT,
                RenewalReminderSender.composeBody(account, daysLeft, plan, billingUrl)
                        .replace('\n', ' '));
    }
}
