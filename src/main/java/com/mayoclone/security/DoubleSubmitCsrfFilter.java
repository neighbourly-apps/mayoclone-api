package com.mayoclone.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Double-submit CSRF guard for the COOKIE-authenticated auth endpoints
 * (POST /api/auth/refresh and /api/auth/logout). These are the only routes that
 * act on the httpOnly {@code refresh_token} cookie, so they need CSRF protection;
 * all Bearer-token routes are stateless and exempt.
 *
 * <p>The check: the non-httpOnly {@code csrf} cookie (readable by JS, set at
 * login/register/refresh) must equal the {@code X-CSRF-Token} request header.
 * A cross-site attacker can trigger the cookie but cannot read it to set the
 * header, so the request is rejected with 403.
 */
public class DoubleSubmitCsrfFilter extends OncePerRequestFilter {

    public static final String CSRF_COOKIE = "csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private boolean isProtected(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri.equals("/api/auth/refresh") || uri.equals("/api/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isProtected(request)) {
            String header = request.getHeader(CSRF_HEADER);
            if (header == null || header.isBlank() || !matchesAnyCsrfCookie(request, header)) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"csrf\",\"message\":\"CSRF token missing or invalid\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * True if the header equals ANY {@code csrf} cookie on the request. A browser
     * can legitimately carry more than one {@code csrf} cookie (e.g. a stale cookie
     * left at a previous path after a cookie-path change), and it sends them all;
     * matching any of them keeps the double-submit guarantee (a cross-site attacker
     * still can't read the value to set the header) while avoiding a spurious 403
     * loop when the server would otherwise read the wrong duplicate first.
     */
    private boolean matchesAnyCsrfCookie(HttpServletRequest request, String header) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie c : cookies) {
            if (CSRF_COOKIE.equals(c.getName())
                    && c.getValue() != null && !c.getValue().isBlank()
                    && c.getValue().equals(header)) {
                return true;
            }
        }
        return false;
    }
}
