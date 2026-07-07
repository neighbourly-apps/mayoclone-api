package com.mayoclone.service;

import com.mayoclone.domain.EmailOtp;
import com.mayoclone.repository.EmailOtpRepository;
import com.mayoclone.security.JwtService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Email OTP verification: generate → email → verify.
 *
 * <p>A 6-digit numeric code is stored HASHED (SHA-256) in {@code email_otp} with a
 * 10-minute expiry and an attempt counter (max 5). Sending a new code invalidates
 * any prior unconsumed code for the email. Delivery goes through {@link OtpSender}
 * (real SMTP or a dev logger). Sends are rate-limited per email+IP (1 / 30s and
 * 5 / hour). A successful verify mints a short-lived {@code email_verify} JWT.
 */
@Service
public class EmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;

    private final EmailOtpRepository repo;
    private final OtpSender otpSender;
    private final JwtService jwtService;
    private final long ttlSeconds;
    private final boolean devMode;

    /** Per (email|ip) send buckets: 1 / 30s AND 5 / hour. JVM-local, like RateLimitFilter. */
    private final ConcurrentHashMap<String, Bucket> sendBuckets = new ConcurrentHashMap<>();

    public EmailOtpService(EmailOtpRepository repo,
                           OtpSender otpSender,
                           JwtService jwtService,
                           @Value("${mayoclone.auth.otp-ttl-seconds:600}") long ttlSeconds,
                           @Value("${mayoclone.auth.otp-dev-mode:true}") boolean devMode) {
        this.repo = repo;
        this.otpSender = otpSender;
        this.jwtService = jwtService;
        this.ttlSeconds = ttlSeconds;
        this.devMode = devMode;
    }

    /** Generate + store + send a code. Returns the plaintext code ONLY in dev mode (else null). */
    @Transactional
    public String send(String rawEmail, String ip) {
        String email = normalize(rawEmail);
        if (!allowSend(email, ip)) {
            throw new OtpException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }

        // Invalidate any prior unconsumed code for this email.
        repo.consumeAllForEmail(email);

        String code = randomCode();
        EmailOtp otp = new EmailOtp();
        otp.setEmail(email);
        otp.setCodeHash(sha256(code));
        otp.setAttempts(0);
        otp.setConsumed(false);
        Instant now = Instant.now();
        otp.setCreatedAt(now);
        otp.setExpiresAt(now.plusSeconds(ttlSeconds));
        repo.save(otp);

        try {
            otpSender.send(email, code);
        } catch (RuntimeException e) {
            // Do not leak delivery failures to the caller (avoids enumeration/timing);
            // the code is stored and can be re-requested. Log for operators.
            log.warn("OTP delivery failed for {}: {}", email, e.toString());
        }

        return devMode ? code : null;
    }

    /**
     * Verify a code. On success: marks it consumed and returns a short-lived
     * {@code email_verify} JWT. On failure: throws {@link OtpException} with
     * {@code invalid_or_expired} (400) or {@code too_many_attempts} (429).
     */
    @Transactional
    public String verify(String rawEmail, String code) {
        String email = normalize(rawEmail);
        EmailOtp otp = repo.findFirstByEmailAndConsumedFalseOrderByCreatedAtDesc(email).orElse(null);

        if (otp == null || otp.isExpired()) {
            throw new OtpException(HttpStatus.BAD_REQUEST, "invalid_or_expired");
        }
        if (otp.getAttempts() >= MAX_ATTEMPTS) {
            throw new OtpException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }

        boolean match = constantTimeEquals(sha256(nullToEmpty(code)), otp.getCodeHash());
        if (!match) {
            otp.setAttempts(otp.getAttempts() + 1);
            repo.save(otp);
            throw new OtpException(HttpStatus.BAD_REQUEST, "invalid_or_expired");
        }

        otp.setConsumed(true);
        repo.save(otp);
        return jwtService.issueEmailVerifyToken(email);
    }

    private boolean allowSend(String email, String ip) {
        String key = email + "|" + (ip == null ? "?" : ip);
        Bucket bucket = sendBuckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(1).refillGreedy(1, Duration.ofSeconds(30)).build())
                .addLimit(Bandwidth.builder().capacity(5).refillGreedy(5, Duration.ofHours(1)).build())
                .build());
        return bucket.tryConsume(1);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** Six digits, zero-padded, cryptographically random (000000–999999). */
    private static String randomCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time compare of the two hex hashes. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
