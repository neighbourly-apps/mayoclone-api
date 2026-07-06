package com.mayoclone.service;

import com.mayoclone.domain.RefreshToken;
import com.mayoclone.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Opaque, rotating refresh tokens with reuse detection.
 *
 * <p>Only a SHA-256 hash of each token is persisted. On {@link #rotate} the
 * presented token is revoked and a fresh one issued in the same family. If a
 * REVOKED token is presented again (a classic theft/replay signal) the WHOLE
 * family is revoked, forcing re-login.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repo;
    private final AuditService auditService;
    private final long refreshTtlSeconds;

    public RefreshTokenService(RefreshTokenRepository repo,
                               AuditService auditService,
                               @Value("${mayoclone.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.repo = repo;
        this.auditService = auditService;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /** The rotated/issued token plus the account it belongs to. */
    public record Issued(Long accountId, String rawToken) {
    }

    /** Issue a brand-new token in a NEW family (login/register). */
    @Transactional
    public Issued issueNewFamily(Long accountId) {
        return persist(accountId, randomToken(), newFamilyId());
    }

    /** Validate + rotate a presented refresh token. */
    @Transactional
    public Issued rotate(String rawToken) {
        RefreshToken existing = repo.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid refresh token"));

        if (existing.isRevoked()) {
            // Reuse of an already-rotated token => likely theft. Nuke the family.
            log.warn("Refresh token reuse detected for family {} (account {}); revoking family",
                    existing.getFamilyId(), existing.getAccountId());
            repo.revokeFamily(existing.getFamilyId());
            auditService.record(existing.getAccountId(), null, "auth.refresh.reuse", "account",
                    String.valueOf(existing.getAccountId()),
                    java.util.Map.of("familyId", existing.getFamilyId()));
            throw new ResponseStatusException(UNAUTHORIZED, "Refresh token reuse detected");
        }
        if (existing.isExpired()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Refresh token expired");
        }

        existing.setRevoked(true);
        repo.save(existing);
        return persist(existing.getAccountId(), randomToken(), existing.getFamilyId());
    }

    /** Revoke a single token (logout). Silent if not found. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        Optional<RefreshToken> t = repo.findByTokenHash(sha256(rawToken));
        t.ifPresent(rt -> {
            rt.setRevoked(true);
            repo.save(rt);
        });
    }

    private Issued persist(Long accountId, String rawToken, String familyId) {
        RefreshToken t = new RefreshToken();
        t.setAccountId(accountId);
        t.setTokenHash(sha256(rawToken));
        t.setFamilyId(familyId);
        t.setRevoked(false);
        Instant now = Instant.now();
        t.setCreatedAt(now);
        t.setExpiresAt(now.plusSeconds(refreshTtlSeconds));
        repo.save(t);
        return new Issued(accountId, rawToken);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String newFamilyId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** SHA-256 is appropriate here: the token is already high-entropy random. */
    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
