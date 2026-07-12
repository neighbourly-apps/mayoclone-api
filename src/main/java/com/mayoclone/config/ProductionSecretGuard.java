package com.mayoclone.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast guard that ABORTS startup under the {@code prod} profile when any
 * security-sensitive property is left on an insecure DEV default.
 *
 * <p>Every one of the checked properties ships with a working local default so the
 * app boots out-of-the-box for development and tests. That convenience is a
 * liability in production: a forgotten env override would boot SILENTLY with a
 * committed JWT secret (anyone can forge a SUPER_ADMIN token), a committed AES key
 * (IMAP passwords / OAuth tokens effectively plaintext), a known inbound HMAC
 * secret (forged order injection), OTP dev-mode (verification bypass), billing
 * dev-mode (free subscriptions), non-Secure cookies, or a localhost/wildcard CORS
 * allowlist. This guard turns each of those into a loud startup failure instead.
 *
 * <p>Runs as an {@link ApplicationRunner} with {@link Order} {@code HIGHEST}, so it
 * fires before any other runner (e.g. {@code AccountSeeder}) can act on a
 * misconfigured context. The actual checks live in the static
 * {@link #findViolations} method so they can be unit-tested without a Spring
 * context or the {@code prod} profile.
 */
@Component
@Profile("prod")
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ProductionSecretGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecretGuard.class);

    /** The committed dev JWT secret from application.yml — MUST NOT reach prod. */
    public static final String DEV_JWT_SECRET =
            "dev-insecure-jwt-secret-change-me-please-0123456789abcdef";
    /** The committed dev inbound HMAC secret from application.yml — MUST NOT reach prod. */
    public static final String DEV_INBOUND_SIGNING_SECRET =
            "dev-inbound-signing-secret-change-me";

    private final String jwtSecret;
    private final String encKey;
    private final String inboundSigningSecret;
    private final boolean otpDevMode;
    private final boolean billingDevMode;
    private final boolean cookieSecure;
    private final String corsOrigins;

    public ProductionSecretGuard(
            @Value("${mayoclone.jwt.secret:}") String jwtSecret,
            @Value("${mayoclone.enc.key:}") String encKey,
            @Value("${mayoclone.inbound.signing-secret:}") String inboundSigningSecret,
            @Value("${mayoclone.auth.otp-dev-mode:false}") boolean otpDevMode,
            @Value("${mayoclone.billing.dev-mode:false}") boolean billingDevMode,
            @Value("${mayoclone.cookie.secure:false}") boolean cookieSecure,
            @Value("${mayoclone.cors.origins:}") String corsOrigins) {
        this.jwtSecret = jwtSecret;
        this.encKey = encKey;
        this.inboundSigningSecret = inboundSigningSecret;
        this.otpDevMode = otpDevMode;
        this.billingDevMode = billingDevMode;
        this.cookieSecure = cookieSecure;
        this.corsOrigins = corsOrigins;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = findViolations(
                jwtSecret, encKey, inboundSigningSecret,
                otpDevMode, billingDevMode, cookieSecure, corsOrigins);
        if (!violations.isEmpty()) {
            String msg = buildMessage(violations);
            // Log before throwing so the reason is visible even if the exception
            // stack gets truncated by the launcher.
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.info("ProductionSecretGuard: all {} production secret checks passed.", CHECK_COUNT);
    }

    /** Number of distinct properties inspected (for logging/tests). */
    public static final int CHECK_COUNT = 7;

    /**
     * Pure validation: returns a list of human-readable violation messages (one per
     * offending property), each naming the property and how to fix it. Empty list =
     * safe for production. Static + argument-driven so it is trivially unit-testable.
     */
    public static List<String> findViolations(
            String jwtSecret,
            String encKey,
            String inboundSigningSecret,
            boolean otpDevMode,
            boolean billingDevMode,
            boolean cookieSecure,
            String corsOrigins) {

        List<String> violations = new ArrayList<>();

        if (isBlank(jwtSecret)) {
            violations.add("mayoclone.jwt.secret is BLANK — set MAYOCLONE_JWT_SECRET to a strong random 32+ byte secret.");
        } else if (jwtSecret.equals(DEV_JWT_SECRET)) {
            violations.add("mayoclone.jwt.secret is the committed DEV default (anyone can forge a SUPER_ADMIN token) — "
                    + "set MAYOCLONE_JWT_SECRET to a strong random 32+ byte secret.");
        }

        if (isBlank(encKey)) {
            violations.add("mayoclone.enc.key is BLANK — the AES converter would fall back to a committed key "
                    + "(IMAP passwords / OAuth tokens effectively plaintext); set MAYOCLONE_ENC_KEY to a base64 32-byte (AES-256) key.");
        }

        if (isBlank(inboundSigningSecret)) {
            violations.add("mayoclone.inbound.signing-secret is BLANK — set MAYOCLONE_INBOUND_SIGNING_SECRET to a strong random secret.");
        } else if (inboundSigningSecret.equals(DEV_INBOUND_SIGNING_SECRET)) {
            violations.add("mayoclone.inbound.signing-secret is the committed DEV default (allows forged inbound order injection) — "
                    + "set MAYOCLONE_INBOUND_SIGNING_SECRET to a strong random secret.");
        }

        if (otpDevMode) {
            violations.add("mayoclone.auth.otp-dev-mode is TRUE (returns the OTP in the HTTP response — verification bypass) — "
                    + "set MAYOCLONE_OTP_DEV_MODE=false.");
        }

        if (billingDevMode) {
            violations.add("mayoclone.billing.dev-mode is TRUE (exposes POST /api/billing/dev-activate — free subscriptions) — "
                    + "set MAYOCLONE_BILLING_DEV_MODE=false.");
        }

        if (!cookieSecure) {
            violations.add("mayoclone.cookie.secure is FALSE (refresh/csrf cookies sent over plaintext HTTP) — "
                    + "set MAYOCLONE_COOKIE_SECURE=true.");
        }

        String cors = corsOrigins == null ? "" : corsOrigins.toLowerCase();
        if (cors.contains("localhost") || cors.contains("127.0.0.1") || cors.contains("*")) {
            violations.add("mayoclone.cors.origins contains a localhost/127.0.0.1/wildcard entry (\"" + corsOrigins
                    + "\") — set MAYOCLONE_CORS_ORIGINS to your exact production HTTPS origin(s).");
        }

        return violations;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String buildMessage(List<String> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("ABORTING STARTUP: the 'prod' profile is active but ")
                .append(violations.size())
                .append(" insecure DEV default(s) were detected. Fix EACH of the following before starting in production:");
        for (String v : violations) {
            sb.append("\n  - ").append(v);
        }
        return sb.toString();
    }
}
