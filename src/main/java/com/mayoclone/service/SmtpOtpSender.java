package com.mayoclone.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Real SMTP {@link OtpSender}. Active ONLY when {@code spring.mail.host} is set,
 * which is also the condition under which Spring Boot auto-configures the
 * {@link JavaMailSender}. Sends a simple plain-text verification email.
 */
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class SmtpOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpOtpSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpOtpSender(JavaMailSender mailSender,
                         @Value("${mayoclone.auth.otp-from:no-reply@mayoclone.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String email, String code) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(email);
        msg.setSubject("Your MayoClone verification code");
        msg.setText("Your MayoClone verification code is " + code + ", valid 10 minutes.");
        mailSender.send(msg);
        log.info("Sent OTP email to {}", email);
    }
}
