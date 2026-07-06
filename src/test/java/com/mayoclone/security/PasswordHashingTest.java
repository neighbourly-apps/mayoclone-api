package com.mayoclone.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the production password hashing (Argon2) used by SecurityConfig. */
class PasswordHashingTest {

    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @Test
    void hashIsNotThePlaintextAndVerifies() {
        String raw = "correct horse battery staple";
        String hash = encoder.encode(raw);

        assertNotEquals(raw, hash);
        assertTrue(hash.startsWith("$argon2"));
        assertTrue(encoder.matches(raw, hash));
        assertFalse(encoder.matches("wrong password", hash));
    }

    @Test
    void twoHashesOfSamePasswordDiffer() {
        String raw = "a-strong-password-123";
        assertNotEquals(encoder.encode(raw), encoder.encode(raw));
    }
}
