package com.mayoclone.db;

import com.mayoclone.domain.IngestJob;
import com.mayoclone.jobs.JobQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-gated Postgres suite for the durable job queue. {@code disabledWithoutDocker
 * = true} makes JUnit SKIP the whole class when Docker is absent, so
 * {@code ./gradlew build} stays GREEN without Docker. When Docker IS present it boots
 * against a REAL Postgres (real Flyway V1–V10 + Hibernate validate) and proves the
 * Postgres-only bits the H2 suites can't: {@code FOR UPDATE SKIP LOCKED} claim safety
 * and the V10 schema (ingest_job + vendor.gmail_* columns + partial-unique dedup).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class IngestJobPostgresContainerTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    private JobQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v10SchemaExistsAndValidates() {
        // A clean context start already proves ddl-auto=validate passed against V10.
        Integer version = jdbc.queryForObject(
                "SELECT MAX(version::int) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertNotNull(version);
        assertTrue(version >= 10, "Flyway should have applied at least V1–V10");

        assertEquals(1, tableCount("ingest_job"), "ingest_job table must exist");
        assertEquals(1, columnCount("vendor", "gmail_history_id"));
        assertEquals(1, columnCount("vendor", "gmail_watch_expiration"));
        // The partial-unique dedup index must exist.
        Integer idx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uq_ingest_job_dedup_active'", Integer.class);
        assertEquals(1, idx, "partial-unique dedup index must exist");
    }

    @Test
    void concurrentClaimNeverGrabsTheSameJobTwice() throws Exception {
        int jobCount = 40;
        for (int i = 0; i < jobCount; i++) {
            queue.enqueue("CONCURRENCY_TYPE", null, null, "{\"n\":" + i + "}", null);
        }

        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<Long> allClaimedIds = ConcurrentHashMap.newKeySet();
        List<Long> claimedWithDupes = new CopyOnWriteArrayList<>();
        List<Thread> workers = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threads; t++) {
            String workerId = "worker-" + t;
            Thread thread = new Thread(() -> {
                try {
                    barrier.await(); // maximise contention
                    List<IngestJob> batch;
                    // Drain: keep claiming until the queue is empty.
                    while (!(batch = queue.claim(workerId, 5)).isEmpty()) {
                        for (IngestJob j : batch) {
                            claimedWithDupes.add(j.getId());
                            allClaimedIds.add(j.getId());
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            workers.add(thread);
            thread.start();
        }
        for (Thread thread : workers) {
            thread.join();
        }

        // SKIP LOCKED guarantee: every job claimed exactly once, none double-grabbed.
        assertEquals(jobCount, allClaimedIds.size(), "every job should be claimed");
        assertEquals(jobCount, claimedWithDupes.size(),
                "no job may be claimed by two workers (SKIP LOCKED)");
    }

    @Test
    void dedupKeyCoalescesUnderPartialUniqueIndex() {
        String dedup = "mailbox-coalesce-" + System.nanoTime();
        Long a = queue.enqueue("COALESCE_TYPE", null, 1L, "{\"h\":1}", dedup);
        Long b = queue.enqueue("COALESCE_TYPE", null, 1L, "{\"h\":2}", dedup);
        assertEquals(a, b, "same dedup key coalesces to one active job");

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ingest_job WHERE dedup_key = ? AND status IN ('PENDING','RUNNING')",
                Integer.class, dedup);
        assertEquals(1, active, "at most one active job per dedup key");
    }

    private Integer tableCount(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table);
    }

    private Integer columnCount(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
    }
}
