package com.mayoclone.service;

import com.mayoclone.domain.IngestJob;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.ingest.gmail.GmailPushProperties;
import com.mayoclone.jobs.JobQueue;
import com.mayoclone.jobs.JobWorker;
import com.mayoclone.jobs.MailboxSyncJobHandler;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.VendorRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-gated Postgres suite for the mailbox poll dispatcher. Skipped entirely when
 * Docker is absent ({@code disabledWithoutDocker=true}), so {@code ./gradlew build}
 * stays GREEN without Docker. Proves the Postgres-only bits the H2 suites can't:
 * the {@code pg_try_advisory_lock} single-dispatcher guard, and that a 200-vendor
 * batch dispatch (even raced across "instances") yields exactly ONE active
 * MAILBOX_SYNC per vendor via the dedupKey, drained through the real worker path.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class MailboxPollDispatcherPostgresContainerTest {

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

    @Autowired private VendorRepository vendorRepo;
    @Autowired private JobQueue queue;
    @Autowired private JobWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private MailboxPollDispatcher dispatcher() {
        GmailPushProperties props = new GmailPushProperties("", "", "", 21_600_000L, true);
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        return new MailboxPollDispatcher(vendorRepo, queue, props, jdbc,
                new AppMetrics(reg), reg, true, 60_000L, 200);
    }

    private Vendor dueVendor() {
        Vendor v = new Vendor();
        v.setAccountId(1L);
        v.setRestaurantName("R-" + System.nanoTime());
        v.setSourceType(MailSourceType.IMAP);
        v.setActive(true);
        v.setUseSsl(true);
        v.setNextPollAt(null); // due immediately
        v.setCreatedAt(Instant.now());
        return vendorRepo.saveAndFlush(v);
    }

    private int activeJobs(Long vendorId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ingest_job WHERE job_type = 'MAILBOX_SYNC' AND vendor_id = ? "
                        + "AND status IN ('PENDING','RUNNING')", Integer.class, vendorId);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- V12 schema

    @Test
    void v12SchemaAndDueIndexExist() {
        Integer version = jdbc.queryForObject(
                "SELECT MAX(version::int) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertNotNull(version);
        assertTrue(version >= 12, "Flyway should have applied at least V1–V12");
        assertEquals(1, columnCount("next_poll_at"));
        assertEquals(1, columnCount("poll_failure_count"));
        assertEquals(1, columnCount("last_sync_error"));
        Integer idx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'idx_vendor_due_poll'", Integer.class);
        assertEquals(1, idx, "due-vendors index must exist");
    }

    // ------------------------------------------------------- advisory-lock guard

    @Test
    void advisoryLockSerializesConcurrentDispatch() throws Exception {
        Vendor v = dueVendor();
        MailboxPollDispatcher dispatcher = dispatcher();

        // Hold the SAME advisory-lock key on a dedicated session: the dispatcher's
        // pg_try_advisory_lock must FAIL, so tick() returns without enqueuing.
        try (Connection held = dataSource.getConnection(); Statement st = held.createStatement()) {
            st.execute("SELECT pg_advisory_lock(" + MailboxPollDispatcher.ADVISORY_LOCK_KEY + ")");

            dispatcher.tick();
            assertEquals(0, activeJobs(v.getId()), "lock held by another session → no enqueue");

            st.execute("SELECT pg_advisory_unlock(" + MailboxPollDispatcher.ADVISORY_LOCK_KEY + ")");
        }

        // Lock free again → tick() now enqueues the due vendor.
        dispatcher.tick();
        assertEquals(1, activeJobs(v.getId()), "lock free → vendor enqueued");
    }

    // -------------------------------------------- 200-vendor batch + dedup + drain

    @Test
    void batchDispatchNeverDuplicatesPerVendorAndDrains() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(dueVendor().getId());
        }
        MailboxPollDispatcher dispatcher = dispatcher();

        // First full batch (batch-size 200) enqueues one job per vendor.
        assertEquals(200, dispatcher.dispatchDue());

        // Reset all to due and race TWO dispatch passes concurrently (as if two instances
        // dispatched the same tick without the lock). The dedupKey mbsync:<id> must still
        // coalesce to exactly ONE active job per vendor.
        jdbc.update("UPDATE vendor SET next_poll_at = NULL WHERE id = ANY(?)",
                (Object) ids.toArray(new Long[0]));
        runConcurrently(dispatcher);

        int dupes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT vendor_id FROM ingest_job WHERE job_type='MAILBOX_SYNC' "
                        + "AND status IN ('PENDING','RUNNING') GROUP BY vendor_id HAVING COUNT(*) > 1) d",
                Integer.class);
        assertEquals(0, dupes, "dedupKey guarantees at most one active MAILBOX_SYNC per vendor");
        for (Long id : ids) {
            assertEquals(1, activeJobs(id), "each vendor has exactly one active job");
        }

        // Drain the real worker CLAIM path (SKIP LOCKED) — every job claimed exactly once.
        int drained = 0;
        List<IngestJob> batch;
        while (!(batch = queue.claim("drain-worker", 50)).isEmpty()) {
            for (IngestJob j : batch) {
                assertEquals(MailboxSyncJobHandler.JOB_TYPE, j.getJobType());
                queue.complete(j.getId()); // complete via the queue (handler covered by unit test)
                drained++;
            }
        }
        assertEquals(200, drained, "the whole batch drains through the claim path once");
    }

    /** Two threads dispatch the same due vendors at once, maximising the enqueue race. */
    private void runConcurrently(MailboxPollDispatcher dispatcher) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                    dispatcher.dispatchDue();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private Integer columnCount(String column) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'vendor' AND column_name = ?",
                Integer.class, column);
    }
}
