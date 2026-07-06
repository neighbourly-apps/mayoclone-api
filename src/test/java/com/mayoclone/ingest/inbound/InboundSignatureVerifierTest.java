package com.mayoclone.ingest.inbound;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HMAC inbound-webhook signature: valid, tampered, replay, and misconfig cases. */
class InboundSignatureVerifierTest {

    private static final String SECRET = "inbound-test-secret";
    private final InboundSignatureVerifier verifier = new InboundSignatureVerifier(SECRET);

    @Test
    void acceptsAValidFreshSignature() {
        String body = "{\"recipient\":\"a@x\",\"from\":\"orders@zoopindia.com\"}";
        long now = Instant.now().getEpochSecond();
        String header = verifier.sign(body, now);

        assertTrue(verifier.verify(header, body, now));
    }

    @Test
    void rejectsATamperedBody() {
        long now = Instant.now().getEpochSecond();
        String header = verifier.sign("{\"a\":1}", now);
        assertFalse(verifier.verify(header, "{\"a\":2}", now));
    }

    @Test
    void rejectsAWrongSecret() {
        long now = Instant.now().getEpochSecond();
        String header = new InboundSignatureVerifier("other-secret").sign("body", now);
        assertFalse(verifier.verify(header, "body", now));
    }

    @Test
    void rejectsReplayOutsideSkewWindow() {
        String body = "body";
        long signedAt = Instant.now().getEpochSecond() - 3600; // 1h old
        String header = verifier.sign(body, signedAt);
        // "now" is an hour after signing → beyond the 5-minute window.
        assertFalse(verifier.verify(header, body, signedAt + 3600));
    }

    @Test
    void rejectsMissingOrMalformedHeader() {
        assertFalse(verifier.verify(null, "body"));
        assertFalse(verifier.verify("", "body"));
        assertFalse(verifier.verify("garbage", "body"));
        assertFalse(verifier.verify("t=123", "body")); // no v1
    }

    @Test
    void rejectsWhenNotConfigured() {
        InboundSignatureVerifier unconfigured = new InboundSignatureVerifier("");
        assertFalse(unconfigured.isConfigured());
        long now = Instant.now().getEpochSecond();
        // Even a "well-formed" header can't be trusted with no secret.
        assertFalse(unconfigured.verify("t=" + now + ",v1=deadbeef", "body", now));
    }
}
