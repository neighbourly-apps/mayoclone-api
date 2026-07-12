package com.mayoclone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSRF guard: reject internal targets at save AND fail closed at dispatch. No network egress. */
class WebhookUrlGuardTest {

    // ------------------------------------------------------------------ save time

    @Test
    void rejectsNonHttpsSchemesAtSave() {
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class,
                () -> WebhookUrlGuard.validateForSave("http://example.com/hook"));
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class,
                () -> WebhookUrlGuard.validateForSave("ftp://example.com/hook"));
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class,
                () -> WebhookUrlGuard.validateForSave("file:///etc/passwd"));
    }

    @Test
    void rejectsLoopbackAndMetadataAndPrivateLiteralsAtSave() {
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class,
                () -> WebhookUrlGuard.validateForSave("https://127.0.0.1/hook"));
        // Cloud metadata endpoint (link-local).
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class,
                () -> WebhookUrlGuard.validateForSave("https://169.254.169.254/latest/meta-data/"));
        // RFC1918 private ranges.
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class,
                () -> WebhookUrlGuard.validateForSave("https://10.0.0.5/hook"));
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class,
                () -> WebhookUrlGuard.validateForSave("https://192.168.1.10/hook"));
        // IPv6 loopback.
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class,
                () -> WebhookUrlGuard.validateForSave("https://[::1]/hook"));
    }

    @Test
    void allowsHttpsPublicOrUnresolvableHostAtSave() {
        // A reserved-TLD host that does not resolve is allowed at save (dispatch re-checks).
        assertDoesNotThrow(() -> WebhookUrlGuard.validateForSave("https://example.test/hook"));
    }

    // ------------------------------------------------------------------ dispatch time

    @Test
    void dispatchFailsClosedForBlockedOrUnresolvableOrNonHttps() {
        assertFalse(WebhookUrlGuard.isSafeToDispatch("https://127.0.0.1/hook"), "loopback blocked");
        assertFalse(WebhookUrlGuard.isSafeToDispatch("https://169.254.169.254/x"), "metadata blocked");
        assertFalse(WebhookUrlGuard.isSafeToDispatch("https://10.1.2.3/x"), "private blocked");
        assertFalse(WebhookUrlGuard.isSafeToDispatch("http://example.com/x"), "non-https blocked");
        assertFalse(WebhookUrlGuard.isSafeToDispatch("https://this-host-does-not-resolve.invalid/x"),
                "unresolvable → fail closed");
        assertFalse(WebhookUrlGuard.isSafeToDispatch(null), "null → fail closed");
        assertFalse(WebhookUrlGuard.isSafeToDispatch("   "), "blank → fail closed");
    }

    @Test
    void dispatchAllowsAPublicHost() {
        // Uses a stable public IP literal so no DNS is required and no external call is made.
        assertTrue(WebhookUrlGuard.isSafeToDispatch("https://1.1.1.1/hook"),
                "a public address passes the guard");
    }
}
