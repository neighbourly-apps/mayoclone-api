package com.mayoclone.db;

import com.mayoclone.domain.Account;
import com.mayoclone.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ONE Docker-gated suite. {@code @Testcontainers(disabledWithoutDocker = true)}
 * makes JUnit SKIP the whole class (context never even starts) when Docker is not
 * available, so {@code ./gradlew build} stays GREEN in a Docker-less environment.
 *
 * <p>When Docker IS available it boots the app against a REAL Postgres, runs the
 * actual Flyway migrations V1–V4 (Hibernate then {@code validate}s the schema, so a
 * clean context start proves the entity↔schema mapping), verifies the
 * {@code audit_log} immutability trigger blocks UPDATE and DELETE, and does a basic
 * repository round-trip.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PostgresFlywayContainerTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Point the app at the container and RE-ENABLE the production data layer for
     * this suite only: real Flyway migrations + Hibernate {@code validate}
     * (overriding the H2 create-drop defaults from application-test.yml).
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountRepository accountRepo;

    @Test
    void flywayMigratedSchemaValidatesAndCoreTablesExist() {
        // A clean context start already proves ddl-auto=validate passed. Double-check
        // the migrated tables are present.
        Integer version = jdbc.queryForObject(
                "SELECT MAX(version::int) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertNotNull(version);
        assertTrue(version >= 4, "Flyway should have applied at least V1–V4");

        for (String table : new String[] {"account", "vendor", "irctc_order", "ingest_failure", "audit_log"}) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertEquals(1, exists, "table '" + table + "' should exist");
        }
    }

    @Test
    void auditLogImmutabilityTriggerBlocksUpdateAndDelete() {
        jdbc.update("INSERT INTO audit_log (action, created_at) VALUES (?, now())", "test.event");
        Long id = jdbc.queryForObject(
                "SELECT id FROM audit_log WHERE action = ? ORDER BY id DESC LIMIT 1", Long.class, "test.event");
        assertNotNull(id);

        // The DB trigger must refuse both UPDATE and DELETE (append-only).
        assertThrows(DataAccessException.class, () ->
                jdbc.update("UPDATE audit_log SET action = ? WHERE id = ?", "tampered", id));
        assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM audit_log WHERE id = ?", id));

        // The original row is still intact and unchanged.
        String action = jdbc.queryForObject("SELECT action FROM audit_log WHERE id = ?", String.class, id);
        assertEquals("test.event", action);
    }

    @Test
    void repositoryRoundTripAgainstRealPostgres() {
        Account a = new Account();
        a.setBusinessName("Container Biz");
        a.setEmail("container-" + System.nanoTime() + "@test.local");
        a.setPasswordHash("noop");
        a.setRole(Account.ROLE_OWNER);
        a.setStatus(Account.STATUS_ACTIVE);
        a.setCreatedAt(Instant.now());

        Long id = accountRepo.save(a).getId();
        assertNotNull(id);
        Account reloaded = accountRepo.findById(id).orElseThrow();
        assertEquals("Container Biz", reloaded.getBusinessName());
    }
}
