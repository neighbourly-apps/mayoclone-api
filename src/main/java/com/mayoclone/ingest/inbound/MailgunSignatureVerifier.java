package com.mayoclone.ingest.inbound;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Deque;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Verifies the signature Mailgun attaches to inbound-route webhooks.
 *
 * <p>Mailgun signs each POST with three form fields:
 * {@code timestamp}, {@code token}, {@code signature}, where
 * <pre>signature = hexHMAC_SHA256(key = MAYOCLONE_MAILGUN_SIGNING_KEY,
 *                                 message = timestamp + token)</pre>
 * The key is Mailgun's <b>HTTP webhook signing key</b> (Dashboard → Sending →
 * Webhooks). Verification is constant-time.
 *
 * <p>This verifier also enforces:
 * <ul>
 *   <li><b>Feature gate</b>: if the signing key is unset, {@link #isConfigured()}
 *       is false and the controller returns 503 (feature disabled).</li>
 *   <li><b>Freshness</b>: {@code timestamp} must be within ±15 minutes (replay guard).</li>
 *   <li><b>Token replay</b>: a bounded, in-memory set of recently-seen tokens blocks
 *       re-delivery of the SAME signed request inside the window.</li>
 * </ul>
 */
@Component
public class MailgunSignatureVerifier {

    /** Mailgun's own default freshness window is generous; we clamp replay to 15 min. */
    static final long MAX_SKEW_SECONDS = 15 * 60;
    /** Cap on the recently-seen-token guard so it can't grow unbounded. */
    private static final int MAX_SEEN_TOKENS = 10_000;

    private final byte[] key;
    private final boolean configured;

    // Bounded LRU-ish token guard: presence check + FIFO eviction. Concurrent-safe.
    private final ConcurrentHashMap<String, Boolean> seenTokens = new ConcurrentHashMap<>();
    private final Deque<String> tokenOrder = new ConcurrentLinkedDeque<>();

    public MailgunSignatureVerifier(@Value("${mayoclone.mailgun.signing-key:}") String signingKey) {
        this.configured = signingKey != null && !signingKey.isBlank();
        this.key = (signingKey == null ? "" : signingKey).getBytes(StandardCharsets.UTF_8);
    }

    /** Test/explicit constructor. */
    public static MailgunSignatureVerifier withKey(String signingKey) {
        return new MailgunSignatureVerifier(signingKey);
    }

    /** False when MAYOCLONE_MAILGUN_SIGNING_KEY is unset → the endpoint is disabled (503). */
    public boolean isConfigured() {
        return configured;
    }

    /** Outcome of a verification attempt, so the controller can map to the right HTTP status. */
    public enum Result {
        /** Signing key unset — feature disabled (→ 503). */
        NOT_CONFIGURED,
        /** timestamp/token/signature missing, malformed, or HMAC mismatch (→ 401). */
        INVALID,
        /** timestamp outside the ±15-min window, or token already seen (→ 401). */
        STALE_OR_REPLAY,
        /** Signature valid, fresh, first time seen (→ proceed). */
        VALID
    }

    public Result verify(String timestamp, String token, String signature) {
        return verify(timestamp, token, signature, Instant.now().getEpochSecond());
    }

    /** Verify against an explicit "now" (epoch seconds) — used by tests for skew cases. */
    public Result verify(String timestamp, String token, String signature, long nowEpochSeconds) {
        if (!configured) {
            return Result.NOT_CONFIGURED;
        }
        if (isBlank(timestamp) || isBlank(token) || isBlank(signature)) {
            return Result.INVALID;
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return Result.INVALID;
        }
        if (Math.abs(nowEpochSeconds - ts) > MAX_SKEW_SECONDS) {
            return Result.STALE_OR_REPLAY;
        }

        byte[] expected = hmac((timestamp + token).getBytes(StandardCharsets.UTF_8));
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return Result.INVALID;
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            return Result.INVALID;
        }

        // Signature is authentic AND fresh — now block replay of this exact token.
        if (seenTokens.putIfAbsent(token, Boolean.TRUE) != null) {
            return Result.STALE_OR_REPLAY;
        }
        tokenOrder.addLast(token);
        while (tokenOrder.size() > MAX_SEEN_TOKENS) {
            String evicted = tokenOrder.pollFirst();
            if (evicted != null) {
                seenTokens.remove(evicted);
            }
        }
        return Result.VALID;
    }

    /** Helper for tests: compute the hex signature Mailgun would send for (timestamp, token). */
    public String sign(String timestamp, String token) {
        return HexFormat.of().formatHex(hmac((timestamp + token).getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
