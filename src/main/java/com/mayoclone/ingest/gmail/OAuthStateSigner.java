package com.mayoclone.ingest.gmail;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs and verifies the OAuth {@code state} parameter for the Gmail connect
 * flow. The state binds the initiating {@code accountId}, a random nonce and an
 * expiry, protected by an HMAC-SHA256 tag so a tampered/expired/forged state is
 * rejected on callback (CSRF + integrity for the OAuth handshake).
 *
 * <p>Wire form: {@code base64url(payload) + "." + base64url(hmac)} where
 * {@code payload = "<accountId>:<nonceHex>:<expiryEpochSeconds>"} and
 * {@code hmac = HMAC-SHA256(secret, payloadBytes)}.
 */
@Component
public class OAuthStateSigner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long DEFAULT_TTL_SECONDS = 600; // 10 minutes

    private final byte[] secret;
    private final long ttlSeconds;

    @Autowired
    public OAuthStateSigner(@Value("${mayoclone.jwt.secret}") String secret) {
        this(secret, DEFAULT_TTL_SECONDS);
    }

    /** Test/explicit constructor. */
    public OAuthStateSigner(String secret, long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /** Result of a successful verify. */
    public record State(long accountId) {
    }

    /** Mint a fresh signed state for the given account (expiry = now + ttl). */
    public String sign(long accountId) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String nonceHex = toHex(nonce);
        long expiry = Instant.now().getEpochSecond() + ttlSeconds;
        String payload = accountId + ":" + nonceHex + ":" + expiry;
        String pB64 = b64(payload.getBytes(StandardCharsets.UTF_8));
        String sigB64 = b64(hmac(payload.getBytes(StandardCharsets.UTF_8)));
        return pB64 + "." + sigB64;
    }

    /**
     * Verify signature + expiry and return the bound account.
     *
     * @throws IllegalArgumentException if the state is malformed, tampered, or expired.
     */
    public State verify(String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("Missing state");
        }
        int dot = state.indexOf('.');
        if (dot <= 0 || dot == state.length() - 1) {
            throw new IllegalArgumentException("Malformed state");
        }
        byte[] payloadBytes;
        byte[] providedSig;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(state.substring(0, dot));
            providedSig = Base64.getUrlDecoder().decode(state.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed state encoding");
        }

        byte[] expectedSig = hmac(payloadBytes);
        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            throw new IllegalArgumentException("Bad state signature");
        }

        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] parts = payload.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed state payload");
        }
        long expiry;
        long accountId;
        try {
            accountId = Long.parseLong(parts[0]);
            expiry = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed state fields");
        }
        if (Instant.now().getEpochSecond() > expiry) {
            throw new IllegalArgumentException("Expired state");
        }
        return new State(accountId);
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

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
