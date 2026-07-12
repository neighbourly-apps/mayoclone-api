package com.mayoclone.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProductionSecretGuard#findViolations}. Pure — no Spring
 * context / no {@code prod} profile needed. Asserts every insecure default is
 * flagged and that a fully-configured prod set passes clean.
 */
class ProductionSecretGuardTest {

    // A known-good production configuration used as the baseline for each case.
    private static final String GOOD_JWT = "a-strong-random-production-secret-0123456789abcdef";
    private static final String GOOD_ENC = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String GOOD_INBOUND = "a-strong-random-inbound-hmac-secret-value";
    private static final String GOOD_CORS = "https://app.mayoclone.com,https://admin.mayoclone.com";

    private List<String> good() {
        return ProductionSecretGuard.findViolations(
                GOOD_JWT, GOOD_ENC, GOOD_INBOUND, false, false, true, GOOD_CORS);
    }

    @Test
    void fullyConfiguredProdSetHasNoViolations() {
        assertTrue(good().isEmpty(), "a fully-configured prod set must pass");
    }

    @Test
    void blankJwtSecretIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                "", GOOD_ENC, GOOD_INBOUND, false, false, true, GOOD_CORS);
        assertViolation(v, "mayoclone.jwt.secret");
    }

    @Test
    void devDefaultJwtSecretIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                ProductionSecretGuard.DEV_JWT_SECRET, GOOD_ENC, GOOD_INBOUND, false, false, true, GOOD_CORS);
        assertViolation(v, "mayoclone.jwt.secret");
    }

    @Test
    void blankEncKeyIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                GOOD_JWT, "", GOOD_INBOUND, false, false, true, GOOD_CORS);
        assertViolation(v, "mayoclone.enc.key");
    }

    @Test
    void blankInboundSecretIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                GOOD_JWT, GOOD_ENC, "", false, false, true, GOOD_CORS);
        assertViolation(v, "mayoclone.inbound.signing-secret");
    }

    @Test
    void devDefaultInboundSecretIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                GOOD_JWT, GOOD_ENC, ProductionSecretGuard.DEV_INBOUND_SIGNING_SECRET,
                false, false, true, GOOD_CORS);
        assertViolation(v, "mayoclone.inbound.signing-secret");
    }

    @Test
    void otpDevModeTrueIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                GOOD_JWT, GOOD_ENC, GOOD_INBOUND, true, false, true, GOOD_CORS);
        assertViolation(v, "mayoclone.auth.otp-dev-mode");
    }

    @Test
    void billingDevModeTrueIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                GOOD_JWT, GOOD_ENC, GOOD_INBOUND, false, true, true, GOOD_CORS);
        assertViolation(v, "mayoclone.billing.dev-mode");
    }

    @Test
    void cookieSecureFalseIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                GOOD_JWT, GOOD_ENC, GOOD_INBOUND, false, false, false, GOOD_CORS);
        assertViolation(v, "mayoclone.cookie.secure");
    }

    @Test
    void corsWithLocalhostIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                GOOD_JWT, GOOD_ENC, GOOD_INBOUND, false, false, true, "http://localhost:5173");
        assertViolation(v, "mayoclone.cors.origins");
    }

    @Test
    void corsWithLoopbackIpIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                GOOD_JWT, GOOD_ENC, GOOD_INBOUND, false, false, true, "https://app.x.com,http://127.0.0.1:3000");
        assertViolation(v, "mayoclone.cors.origins");
    }

    @Test
    void corsWithWildcardIsFlagged() {
        List<String> v = ProductionSecretGuard.findViolations(
                GOOD_JWT, GOOD_ENC, GOOD_INBOUND, false, false, true, "*");
        assertViolation(v, "mayoclone.cors.origins");
    }

    @Test
    void allDefaultsAtOnceFlagEveryProperty() {
        List<String> v = ProductionSecretGuard.findViolations(
                ProductionSecretGuard.DEV_JWT_SECRET, "",
                ProductionSecretGuard.DEV_INBOUND_SIGNING_SECRET,
                true, true, false, "http://localhost:5173");
        // One violation per checked property.
        assertEquals(ProductionSecretGuard.CHECK_COUNT, v.size(),
                "every insecure default should be reported exactly once");
        assertViolation(v, "mayoclone.jwt.secret");
        assertViolation(v, "mayoclone.enc.key");
        assertViolation(v, "mayoclone.inbound.signing-secret");
        assertViolation(v, "mayoclone.auth.otp-dev-mode");
        assertViolation(v, "mayoclone.billing.dev-mode");
        assertViolation(v, "mayoclone.cookie.secure");
        assertViolation(v, "mayoclone.cors.origins");
    }

    private static void assertViolation(List<String> violations, String propertyName) {
        boolean present = violations.stream().anyMatch(s -> s.contains(propertyName));
        assertTrue(present, "expected a violation naming " + propertyName + " but got " + violations);
        // A single-cause case should isolate exactly one violation.
    }

    @Test
    void nullValuesTreatedAsBlank() {
        List<String> v = ProductionSecretGuard.findViolations(
                null, null, null, false, false, true, null);
        assertViolation(v, "mayoclone.jwt.secret");
        assertViolation(v, "mayoclone.enc.key");
        assertViolation(v, "mayoclone.inbound.signing-secret");
        assertFalse(v.stream().anyMatch(s -> s.contains("mayoclone.cors.origins")),
                "null CORS is blank, not a localhost/wildcard match");
    }
}
