package com.mayoclone.security;

import com.mayoclone.billing.BillingProperties;
import com.mayoclone.domain.Account;
import com.mayoclone.repository.AccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * The real billing gate. Runs AFTER JWT auth: for an authenticated {@code /api/**}
 * request, it loads the caller's account and — when the subscription is neither a
 * live trial nor a paid period — responds <b>402 Payment Required</b>
 * {@code {"error":"subscription_required"}}.
 *
 * <p>Disabled entirely when {@code mayoclone.billing.enforce=false}. Exempt paths
 * ({@code /api/auth/**}, {@code /api/billing/**}, {@code /actuator/**}) always pass
 * so a locked-out user can still log in/out, view status, and pay.
 */
@Component
public class SubscriptionEnforcementFilter extends OncePerRequestFilter {

    private final AccountRepository accountRepo;
    private final BillingProperties props;

    public SubscriptionEnforcementFilter(AccountRepository accountRepo, BillingProperties props) {
        this.accountRepo = accountRepo;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!props.isEnforce() || !applies(request)) {
            chain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AccountPrincipal p)) {
            // Not authenticated — let the security chain handle it (401).
            chain.doFilter(request, response);
            return;
        }
        Account account = accountRepo.findById(p.id()).orElse(null);
        if (account != null && !account.isSubscriptionActive(Instant.now())) {
            response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED); // 402
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"subscription_required\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Only gate authenticated /api/** calls; exempt auth, billing, and actuator. */
    private boolean applies(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return false;
        }
        return !(path.startsWith("/api/auth/")
                || path.startsWith("/api/billing/")
                || path.startsWith("/actuator/"));
    }
}
