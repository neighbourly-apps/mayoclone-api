package com.mayoclone.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-IP rate limiting (Bucket4j).
 *
 * <ul>
 *   <li>Auth endpoints (POST /api/auth/login and /api/auth/register): 10 req/min/IP.</li>
 *   <li>A looser global cap: 300 req/min/IP across all endpoints.</li>
 * </ul>
 *
 * On exhaustion returns 429 with a {@code Retry-After} header (seconds). Buckets
 * are per JVM instance; a distributed deployment would back this with Redis.
 *
 * <p>Client-IP resolution is anti-spoofing aware. When {@code trustForwardedFor}
 * is false (default) the socket peer ({@link HttpServletRequest#getRemoteAddr()})
 * is the bucket key — a forged {@code X-Forwarded-For} cannot rotate it. When true
 * (only behind a trusted proxy/LB that appends the real client IP) the LAST,
 * proxy-appended XFF hop is used — never the leftmost, client-controlled value.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, Bucket> authBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> globalBuckets = new ConcurrentHashMap<>();

    private final boolean trustForwardedFor;

    public RateLimitFilter(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    private static Bucket authBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(10)
                        .refillGreedy(10, Duration.ofMinutes(1)).build())
                .build();
    }

    private static Bucket globalBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(300)
                        .refillGreedy(300, Duration.ofMinutes(1)).build())
                .build();
    }

    private boolean isAuthLimited(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri.equals("/api/auth/login") || uri.equals("/api/auth/register");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(request);

        Bucket global = globalBuckets.computeIfAbsent(ip, k -> globalBucket());
        if (!tryConsume(global, response)) {
            return;
        }
        if (isAuthLimited(request)) {
            Bucket auth = authBuckets.computeIfAbsent(ip, k -> authBucket());
            if (!tryConsume(auth, response)) {
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean tryConsume(Bucket bucket, HttpServletResponse response) throws IOException {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return true;
        }
        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"Too many requests\"}");
        return false;
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Use the LAST hop: the entry appended by the closest trusted proxy
                // (the IP that actually connected to it). The leftmost value is
                // supplied by the client and is spoofable, so it is never used.
                String[] hops = forwarded.split(",");
                String last = hops[hops.length - 1].trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        // Untrusted network (or no XFF): key on the real socket peer, which a
        // client cannot forge.
        return request.getRemoteAddr();
    }
}
