package com.mayoclone.security;

/**
 * The authenticated caller, derived SOLELY from a verified JWT and stored as the
 * Spring Security principal. {@link #id()} is the tenant boundary: services use it
 * to scope every vendor/order query. It is NEVER read from a request body/param.
 */
public record AccountPrincipal(Long id, String email, String role) {
}
