package com.mayoclone.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies stateless HS256 access tokens.
 *
 * <p>Claims: {@code sub}=accountId, {@code email}, {@code role}. TTL ~15 min
 * (configurable). The signing secret comes from {@code mayoclone.jwt.secret}
 * (env {@code MAYOCLONE_JWT_SECRET}); it must be >= 32 bytes for HS256.
 */
@Service
public class JwtService {

    /** Purpose claim for the short-lived token minted after email OTP verification. */
    public static final String PURPOSE_EMAIL_VERIFY = "email_verify";

    private final SecretKey key;
    private final String issuer;
    private final long accessTtlSeconds;
    private final long emailVerifyTtlSeconds;

    public JwtService(
            @Value("${mayoclone.jwt.secret}") String secret,
            @Value("${mayoclone.jwt.issuer:mayoclone}") String issuer,
            @Value("${mayoclone.jwt.access-ttl-seconds:900}") long accessTtlSeconds,
            @Value("${mayoclone.auth.email-verify-ttl-seconds:900}") long emailVerifyTtlSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "mayoclone.jwt.secret must be at least 32 bytes for HS256 (set MAYOCLONE_JWT_SECRET)");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = issuer;
        this.accessTtlSeconds = accessTtlSeconds;
        this.emailVerifyTtlSeconds = emailVerifyTtlSeconds;
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }

    /** Mint a signed access token for the given account. */
    public String issueAccessToken(Long accountId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(accountId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Mint a short-lived (default 15 min) token proving control of {@code email}.
     * Claims: {@code sub}=email (lower-cased), {@code purpose=email_verify}.
     */
    public String issueEmailVerifyToken(String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(email == null ? null : email.trim().toLowerCase())
                .claim("purpose", PURPOSE_EMAIL_VERIFY)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(emailVerifyTtlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Verify an {@code email_verify} token and return the verified email (its
     * {@code sub}), or {@code null} if missing/invalid/expired/wrong-purpose.
     */
    public String parseEmailVerifiedEmail(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!PURPOSE_EMAIL_VERIFY.equals(claims.get("purpose", String.class))) {
                return null;
            }
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Verify signature + expiry and return the principal, or {@code null} if the
     * token is missing/invalid/expired.
     */
    public AccountPrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long id = Long.valueOf(claims.getSubject());
            String email = claims.get("email", String.class);
            String role = claims.get("role", String.class);
            return new AccountPrincipal(id, email, role);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
