package com.mayoclone.service;

import com.mayoclone.domain.Account;
import com.mayoclone.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Ensures a seeded DEMO tenant exists so the public {@code POST /api/demo/ingest}
 * flow has an account to attribute its sample orders to.
 *
 * <p>Email: {@value #DEMO_EMAIL}. Dev password: {@value #DEMO_PASSWORD} (login
 * works locally; prod should disable/remove this seed). Only created when absent,
 * so operator edits are never overwritten.
 */
@Component
@Order(1)
public class AccountSeeder implements CommandLineRunner {

    public static final String DEMO_EMAIL = "demo@mayoclone.local";
    public static final String DEMO_PASSWORD = "demo-password-1"; // dev-only, min 10 chars

    /** Platform super-admin: sees EVERY tenant, never subscription-gated. */
    public static final String SUPERADMIN_EMAIL = "superadmin@mayoclone.local";
    public static final String SUPERADMIN_PASSWORD = "superadmin123"; // dev-only

    private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);

    private final AccountRepository accountRepo;
    private final PasswordEncoder passwordEncoder;

    public AccountSeeder(AccountRepository accountRepo, PasswordEncoder passwordEncoder) {
        this.accountRepo = accountRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        seedSuperAdmin();
        if (accountRepo.existsByEmailIgnoreCase(DEMO_EMAIL)) {
            return;
        }
        Account a = new Account();
        a.setBusinessName("Demo Restaurant");
        a.setEmail(DEMO_EMAIL);
        a.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        a.setStationCode("NDLS");
        a.setStationName("New Delhi");
        a.setRole(Account.ROLE_ADMIN); // demo account is ADMIN so it can also manage aggregators
        a.setStatus(Account.STATUS_ACTIVE);
        a.setCreatedAt(Instant.now());
        // Grandfather the seeded demo tenant: ACTIVE with a 10-year runway so the
        // billing gate never locks it out (mirrors the V14 grandfathering UPDATE).
        a.setSubscriptionStatus(com.mayoclone.domain.SubscriptionStatus.ACTIVE);
        a.setCurrentPeriodEnd(Instant.now().plus(3650, java.time.temporal.ChronoUnit.DAYS));
        a.setPlan("pro-monthly");
        accountRepo.save(a);
        log.info("Seeded demo account {}", DEMO_EMAIL);
    }

    /**
     * Ensures the platform super-admin exists. ACTIVE with a 100-year runway so the
     * billing gate never locks it out (belt-and-braces alongside the filter's
     * SUPER_ADMIN exemption). Only created when absent, so operator edits stick.
     */
    private void seedSuperAdmin() {
        if (accountRepo.existsByEmailIgnoreCase(SUPERADMIN_EMAIL)) {
            return;
        }
        Account a = new Account();
        a.setBusinessName("Platform Super Admin");
        a.setEmail(SUPERADMIN_EMAIL);
        a.setPasswordHash(passwordEncoder.encode(SUPERADMIN_PASSWORD));
        a.setRole(Account.ROLE_SUPER_ADMIN);
        a.setStatus(Account.STATUS_ACTIVE);
        a.setEmailVerified(true);
        a.setCreatedAt(Instant.now());
        a.setSubscriptionStatus(com.mayoclone.domain.SubscriptionStatus.ACTIVE);
        a.setCurrentPeriodEnd(Instant.now().plus(36500, java.time.temporal.ChronoUnit.DAYS));
        a.setPlan("platform");
        accountRepo.save(a);
        log.info("Seeded platform super-admin {}", SUPERADMIN_EMAIL);
    }
}
