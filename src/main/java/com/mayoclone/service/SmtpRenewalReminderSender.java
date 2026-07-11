package com.mayoclone.service;

import com.mayoclone.billing.BillingDtos.PlanDto;
import com.mayoclone.domain.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Real SMTP {@link RenewalReminderSender}. Active ONLY when {@code spring.mail.host}
 * is set (same condition under which Spring Boot auto-configures {@link JavaMailSender}
 * and {@link SmtpOtpSender}). Sends a plain-text branded reminder.
 */
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class SmtpRenewalReminderSender implements RenewalReminderSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpRenewalReminderSender.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String billingUrl;

    public SmtpRenewalReminderSender(
            JavaMailSender mailSender,
            @Value("${mayoclone.auth.otp-from:no-reply@mayoclone.local}") String from,
            @Value("${mayoclone.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.billingUrl = frontendBaseUrl.replaceAll("/+$", "") + "/billing";
    }

    @Override
    public void send(Account account, int daysLeft, PlanDto plan) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(account.getEmail());
        msg.setSubject(SUBJECT);
        msg.setText(RenewalReminderSender.composeBody(account, daysLeft, plan, billingUrl));
        mailSender.send(msg);
        log.info("Sent renewal reminder to {} ({} days left)", account.getEmail(), daysLeft);
    }
}
