package com.mayoclone.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless Spring Security lockdown for the API.
 *
 * <ul>
 *   <li>All {@code /api/**} routes require a valid Bearer JWT, except the public
 *       auth + inbound + demo routes and actuator health/info.</li>
 *   <li>Aggregator writes require role ADMIN.</li>
 *   <li>Custom filters (in order): rate limit → double-submit CSRF → JWT auth.</li>
 *   <li>Security headers (HSTS, nosniff, DENY frames, referrer policy, API CSP).</li>
 *   <li>CORS allowlist from {@code mayoclone.cors.origins}, credentials enabled.</li>
 *   <li>Spring's stateful CSRF is disabled; cookie routes use our double-submit filter.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final SubscriptionEnforcementFilter subscriptionEnforcementFilter;
    private final String corsOrigins;

    public SecurityConfig(JwtService jwtService,
                          SubscriptionEnforcementFilter subscriptionEnforcementFilter,
                          @Value("${mayoclone.cors.origins:http://localhost:5173}") String corsOrigins) {
        this.jwtService = jwtService;
        this.subscriptionEnforcementFilter = subscriptionEnforcementFilter;
        this.corsOrigins = corsOrigins;
    }

    /** Argon2id password hashing (Spring Security 5.8+ defaults; needs BouncyCastle). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Stateful CSRF disabled: API is Bearer/stateless. Cookie routes
                // (refresh/logout) are protected by DoubleSubmitCsrfFilter instead.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Missing/invalid authentication -> 401 (not the servlet default 403).
                // Critical for the SPA: its axios interceptor only refreshes the access
                // token on a 401, so an expired/absent JWT must surface as 401 to trigger
                // silent refresh. Authenticated-but-forbidden (e.g. non-admin write) still
                // yields 403 via the default AccessDeniedHandler.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentTypeOptions(opt -> {})
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(ref -> ref.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
                .authorizeHttpRequests(auth -> auth
                        // Permit the internal ERROR forward: Boot re-dispatches to
                        // /error after a denial, and that dispatch runs with a cleared
                        // (anonymous) SecurityContext. Without this, an authenticated
                        // non-admin's 403 gets overwritten to 401 by the entry point on
                        // the error re-dispatch (which would then trip the SPA's refresh/
                        // logout path). Securing only REQUEST/ASYNC keeps the real status.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // --- Public ---
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout",
                                "/api/auth/otp/send", "/api/auth/otp/verify").permitAll()
                        .requestMatchers("/api/inbound/**").permitAll()
                        // Razorpay server webhook: PUBLIC route, authenticated by its
                        // X-Razorpay-Signature (verified in BillingService).
                        .requestMatchers(HttpMethod.POST, "/api/billing/webhook").permitAll()
                        .requestMatchers("/api/demo/**").permitAll()
                        // STOMP/SockJS handshake is public; the CONNECT frame is JWT-authed
                        // by StompAuthChannelInterceptor (rejects a missing/invalid token).
                        .requestMatchers("/ws/**").permitAll()
                        // Gmail OAuth callback: public route, but the SIGNED state is
                        // validated in the handler to recover the account. /connect stays authenticated.
                        .requestMatchers(HttpMethod.GET, "/api/mail/gmail/callback").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Public read of uploaded menu photos (non-sensitive). The
                        // POST /api/uploads/image stays authenticated via /api/**.
                        .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
                        // --- Platform super-admin: cross-tenant, sees ALL accounts ---
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        // --- Aggregator: read = any authenticated; write = ADMIN ---
                        .requestMatchers(HttpMethod.GET, "/api/aggregators/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/aggregators/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/aggregators/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/aggregators/**").hasRole("ADMIN")
                        // --- Everything else under /api requires a valid bearer token ---
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                // Filter order: rate limit -> CSRF -> JWT -> billing gate -> (framework auth)
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class)
                // The billing gate runs right after JWT auth so it sees the principal;
                // it 402s expired accounts on protected /api/** calls (config-gated).
                .addFilterAfter(subscriptionEnforcementFilter, JwtAuthenticationFilter.class)
                .addFilterBefore(new DoubleSubmitCsrfFilter(), JwtAuthenticationFilter.class)
                .addFilterBefore(new RateLimitFilter(), DoubleSubmitCsrfFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(corsOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        // PATCH is used by many endpoints (menu availability, order status/assign,
        // rider updates, …). Omitting it made the browser's PATCH requests fail CORS
        // with 403 "Invalid CORS request".
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-CSRF-Token"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
