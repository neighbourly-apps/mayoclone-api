package com.mayoclone.service;

/**
 * Delivers a verification code to an email address. Two implementations exist:
 * {@link SmtpOtpSender} (real email, active only when {@code spring.mail.host} is
 * configured) and {@link LoggingOtpSender} (dev fallback, logs at INFO).
 */
public interface OtpSender {

    /** Deliver {@code code} to {@code email}. Must not throw for transient issues in dev. */
    void send(String email, String code);
}
