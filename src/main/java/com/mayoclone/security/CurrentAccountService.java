package com.mayoclone.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * The single, authoritative way for services to learn WHO is calling. It reads
 * the {@link AccountPrincipal} out of the SecurityContext (populated from the
 * verified JWT). Tenant scoping in every service goes through
 * {@link #accountId()} — never through a request body/param.
 */
@Component
public class CurrentAccountService {

    /** @return the authenticated principal, or 401 if there is none. */
    public AccountPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AccountPrincipal p) {
            return p;
        }
        throw new ResponseStatusException(UNAUTHORIZED, "Authentication required");
    }

    /** @return the caller's tenant id — the value every scoped query filters on. */
    public Long accountId() {
        return require().id();
    }
}
