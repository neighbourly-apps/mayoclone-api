package com.mayoclone.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev/no-SMTP fallback {@link OtpSender}: logs the code at INFO so a local
 * developer can read it from the console. Active whenever {@code spring.mail.host}
 * is NOT configured (or explicitly {@code false}); {@link SmtpOtpSender} takes
 * over when a real SMTP host is set.
 */
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host", havingValue = "false", matchIfMissing = true)
public class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String email, String code) {
        log.info("[DEV OTP] MayoClone verification code for {} is {} (valid 10 minutes)", email, code);
    }
}
