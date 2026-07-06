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

    private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);

    private final AccountRepository accountRepo;
    private final PasswordEncoder passwordEncoder;

    public AccountSeeder(AccountRepository accountRepo, PasswordEncoder passwordEncoder) {
        this.accountRepo = accountRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
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
        accountRepo.save(a);
        log.info("Seeded demo account {}", DEMO_EMAIL);
    }
}
