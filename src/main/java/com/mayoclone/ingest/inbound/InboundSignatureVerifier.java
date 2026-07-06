package com.mayoclone.ingest.inbound;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Verifies the HMAC signature on inbound-email webhook requests.
 *
 * <p>Header form: {@code X-Mayo-Signature: t=<unixTs>,v1=<hexHmac>} where
 * {@code hmac = HMAC-SHA256(secret, "<t>.<rawBody>")} and {@code secret} is
 * {@code MAYOCLONE_INBOUND_SIGNING_SECRET}. A request is rejected when the header
 * is missing/malformed, the timestamp skew exceeds 5 minutes (replay guard), or
 * the HMAC does not match (constant-time compare).
 */
@Component
public class InboundSignatureVerifier {

    public static final String HEADER = "X-Mayo-Signature";
    private static final long MAX_SKEW_SECONDS = 300; // 5 minutes

    private final byte[] secret;
    private final boolean configured;

    public InboundSignatureVerifier(@Value("${mayoclone.inbound.signing-secret:}") String secret) {
        this.configured = secret != null && !secret.isBlank();
        this.secret = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
    }

    /** Test/explicit constructor. */
    public static InboundSignatureVerifier withSecret(String secret) {
        return new InboundSignatureVerifier(secret);
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * @param signatureHeader the raw {@code X-Mayo-Signature} header value
     * @param rawBody         the exact request body bytes that were signed
     * @return true iff the signature is present, unexpired, and valid
     */
    public boolean verify(String signatureHeader, String rawBody) {
        return verify(signatureHeader, rawBody, Instant.now().getEpochSecond());
    }

    /** Verify against an explicit "now" (seconds) — used by tests for skew cases. */
    public boolean verify(String signatureHeader, String rawBody, long nowEpochSeconds) {
        if (!configured || signatureHeader == null || signatureHeader.isBlank() || rawBody == null) {
            return false;
        }
        String tPart = null;
        String v1Part = null;
        for (String segment : signatureHeader.split(",")) {
            String s = segment.trim();
            int eq = s.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = s.substring(0, eq).trim();
            String v = s.substring(eq + 1).trim();
            if ("t".equals(k)) {
                tPart = v;
            } else if ("v1".equals(k)) {
                v1Part = v;
            }
        }
        if (tPart == null || v1Part == null) {
            return false;
        }

        long ts;
        try {
            ts = Long.parseLong(tPart);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowEpochSeconds - ts) > MAX_SKEW_SECONDS) {
            return false; // replay / stale
        }

        byte[] expected = hmac((tPart + "." + rawBody).getBytes(StandardCharsets.UTF_8));
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(v1Part.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    /** Helper for producers/tests: build a valid header value for a body + timestamp. */
    public String sign(String rawBody, long tsEpochSeconds) {
        byte[] mac = hmac((tsEpochSeconds + "." + rawBody).getBytes(StandardCharsets.UTF_8));
        return "t=" + tsEpochSeconds + ",v1=" + HexFormat.of().formatHex(mac);
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }
}
