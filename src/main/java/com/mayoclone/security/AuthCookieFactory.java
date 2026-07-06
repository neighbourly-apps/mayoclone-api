package com.mayoclone.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the auth cookies. The refresh token is httpOnly (JS cannot read it); the
 * csrf token is deliberately readable by JS so the SPA can echo it in the
 * {@code X-CSRF-Token} header (double-submit). Both are scoped to {@code /api/auth}
 * and SameSite=Lax. {@code Secure} is env-driven (must be true in prod/HTTPS).
 */
@Component
public class AuthCookieFactory {

    public static final String REFRESH_COOKIE = "refresh_token";
    public static final String CSRF_COOKIE = DoubleSubmitCsrfFilter.CSRF_COOKIE;
    private static final String PATH = "/api/auth";

    private final boolean secure;
    private final long refreshTtlSeconds;

    public AuthCookieFactory(
            @Value("${mayoclone.cookie.secure:false}") boolean secure,
            @Value("${mayoclone.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.secure = secure;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public ResponseCookie refreshCookie(String rawToken) {
        return ResponseCookie.from(REFRESH_COOKIE, rawToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(Duration.ofSeconds(refreshTtlSeconds))
                .build();
    }

    public ResponseCookie csrfCookie(String csrfValue) {
        return ResponseCookie.from(CSRF_COOKIE, csrfValue)
                .httpOnly(false)  // must be readable by the SPA
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(Duration.ofSeconds(refreshTtlSeconds))
                .build();
    }

    public ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(secure).sameSite("Lax").path(PATH).maxAge(0).build();
    }

    public ResponseCookie clearCsrfCookie() {
        return ResponseCookie.from(CSRF_COOKIE, "")
                .httpOnly(false).secure(secure).sameSite("Lax").path(PATH).maxAge(0).build();
    }
}
