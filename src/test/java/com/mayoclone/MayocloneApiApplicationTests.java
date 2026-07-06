package com.mayoclone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full context (security, JPA, seeders, scheduling) against H2 in
 * PostgreSQL-compatibility mode with Flyway disabled — so it runs anywhere,
 * including CI without Docker.
 */
@SpringBootTest
@ActiveProfiles("test")
class MayocloneApiApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context starts cleanly with all wiring in place.
    }
}
