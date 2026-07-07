package com.mayoclone.ingest.inbound;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Mailgun webhook signature: valid, tampered, stale, token-replay, and disabled cases. */
class MailgunSignatureVerifierTest {

    private static final String KEY = "mailgun-test-signing-key";
    private final MailgunSignatureVerifier verifier = MailgunSignatureVerifier.withKey(KEY);

    @Test
    void acceptsAValidFreshSignature() {
        long now = Instant.now().getEpochSecond();
        String ts = String.valueOf(now);
        String token = "tok-valid-" + now;
        String sig = verifier.sign(ts, token);

        assertEquals(MailgunSignatureVerifier.Result.VALID, verifier.verify(ts, token, sig, now));
    }

    @Test
    void rejectsATamperedSignature() {
        long now = Instant.now().getEpochSecond();
        String ts = String.valueOf(now);
        assertEquals(MailgunSignatureVerifier.Result.INVALID,
                verifier.verify(ts, "tok-tamper", "deadbeef", now));
    }

    @Test
    void rejectsAWrongKey() {
        long now = Instant.now().getEpochSecond();
        String ts = String.valueOf(now);
        String token = "tok-wrongkey-" + now;
        String sig = MailgunSignatureVerifier.withKey("other-key").sign(ts, token);
        assertEquals(MailgunSignatureVerifier.Result.INVALID, verifier.verify(ts, token, sig, now));
    }

    @Test
    void rejectsAStaleTimestampOutsideFifteenMinutes() {
        long signedAt = Instant.now().getEpochSecond() - 3600; // 1h old
        String ts = String.valueOf(signedAt);
        String token = "tok-stale";
        String sig = verifier.sign(ts, token);
        // "now" is an hour later → beyond the 15-minute window.
        assertEquals(MailgunSignatureVerifier.Result.STALE_OR_REPLAY,
                verifier.verify(ts, token, sig, signedAt + 3600));
    }

    @Test
    void blocksTokenReplayWithinWindow() {
        long now = Instant.now().getEpochSecond();
        String ts = String.valueOf(now);
        String token = "tok-replay-once";
        String sig = verifier.sign(ts, token);

        assertEquals(MailgunSignatureVerifier.Result.VALID, verifier.verify(ts, token, sig, now));
        // Same token again → replay.
        assertEquals(MailgunSignatureVerifier.Result.STALE_OR_REPLAY,
                verifier.verify(ts, token, sig, now));
    }

    @Test
    void rejectsMissingFields() {
        long now = Instant.now().getEpochSecond();
        assertEquals(MailgunSignatureVerifier.Result.INVALID, verifier.verify(null, "t", "s", now));
        assertEquals(MailgunSignatureVerifier.Result.INVALID, verifier.verify("1", null, "s", now));
        assertEquals(MailgunSignatureVerifier.Result.INVALID, verifier.verify("1", "t", null, now));
        assertEquals(MailgunSignatureVerifier.Result.INVALID, verifier.verify("notanumber", "t", "aa", now));
    }

    @Test
    void reportsNotConfiguredWhenKeyMissing() {
        MailgunSignatureVerifier unconfigured = MailgunSignatureVerifier.withKey("");
        long now = Instant.now().getEpochSecond();
        assertEquals(MailgunSignatureVerifier.Result.NOT_CONFIGURED,
                unconfigured.verify(String.valueOf(now), "tok", "aa", now));
    }
}
