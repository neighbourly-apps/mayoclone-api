package com.mayoclone.billing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Razorpay HMAC-SHA256 signature helpers (lower-case hex), matching Razorpay's
 * documented scheme:
 * <ul>
 *   <li>Payment verify: {@code HMAC(key_secret, order_id + "|" + payment_id)}.</li>
 *   <li>Webhook: {@code HMAC(webhook_secret, rawBody)} vs {@code X-Razorpay-Signature}.</li>
 * </ul>
 * Comparison is constant-time.
 */
public final class RazorpaySignature {

    private RazorpaySignature() {
    }

    /** Lower-case hex HMAC-SHA256 of {@code data} under {@code secret}. */
    public static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    /** Constant-time compare of an expected HMAC (over {@code data}) against {@code provided} hex. */
    public static boolean verify(String secret, String data, String provided) {
        if (secret == null || secret.isBlank() || provided == null || provided.isBlank()) {
            return false;
        }
        byte[] expected = hmacHex(secret, data).getBytes(StandardCharsets.UTF_8);
        byte[] got = provided.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, got);
    }
}
