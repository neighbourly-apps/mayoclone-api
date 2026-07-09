package com.mayoclone.jobs;

import com.mayoclone.domain.IngestJob;
import com.mayoclone.domain.IngestJobStatus;
import com.mayoclone.repository.IngestJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed (no Docker) exercise of the durable job queue. Drives {@link JobQueue}
 * and {@link JobWorker} directly with the scheduler disabled
 * ({@code mayoclone.jobs.enabled=false} in the test profile). The single-threaded
 * claim path works on H2 without SKIP LOCKED; real concurrent SKIP LOCKED is proven
 * in the Docker-gated {@code IngestJobPostgresContainerTest}.
 *
 * <p>NOT {@code @Transactional}: the queue methods each open their own transaction and
 * we assert committed state across calls, so this suite cleans up after itself.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobQueueH2Test {

    @Autowired
    private JobQueue queue;

    @Autowired
    private JobWorker worker;

    @Autowired
    private IngestJobRepository repo;

    @Autowired
    private RecordingTestHandler testHandler;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String uniqueType() {
        return "TEST_TYPE_" + SEQ.incrementAndGet() + "_" + System.nanoTime();
    }

    // ------------------------------------------------------------- enqueue/coalesce

    @Test
    void enqueueInsertsPendingRunnableJob() {
        Long id = queue.enqueue(uniqueType(), 7L, 9L, "{\"historyId\":\"1\"}", null);
        IngestJob job = repo.findById(id).orElseThrow();
        assertEquals(IngestJobStatus.PENDING, job.getStatus());
        assertEquals(0, job.getAttempts());
        assertEquals(7L, job.getAccountId());
        assertEquals(9L, job.getVendorId());
        assertNotNull(job.getRunAfter());
        assertTrue(!job.getRunAfter().isAfter(Instant.now().plusSeconds(1)), "run_after should be ~now");
    }

    @Test
    void enqueueWithDedupKeyCoalescesIntoOneJob() {
        String type = uniqueType();
        String dedup = "mailbox-" + System.nanoTime();

        Long first = queue.enqueue(type, null, 1L, "{\"historyId\":\"100\"}", dedup);
        Long second = queue.enqueue(type, null, 1L, "{\"historyId\":\"200\"}", dedup);
        Long third = queue.enqueue(type, null, 1L, "{\"historyId\":\"300\"}", dedup);

        // Same row every time — a burst coalesces to ONE job.
        assertEquals(first, second);
        assertEquals(first, third);

        IngestJob job = repo.findById(first).orElseThrow();
        // Payload is refreshed to the newest push.
        assertEquals("{\"historyId\":\"300\"}", job.getPayload());
        assertEquals(IngestJobStatus.PENDING, job.getStatus());
    }

    // ---------------------------------------------------------------------- claim

    @Test
    void claimFlipsPendingToRunningAndSetsLock() {
        Long id = queue.enqueue(uniqueType(), null, null, "{}", null);
        List<IngestJob> claimed = queue.claim("worker-A", 10);

        IngestJob mine = claimed.stream().filter(j -> j.getId().equals(id)).findFirst().orElseThrow();
        assertEquals(IngestJobStatus.RUNNING, mine.getStatus());
        assertEquals("worker-A", mine.getLockedBy());
        assertNotNull(mine.getLockedAt());
    }

    @Test
    void claimSkipsJobsWhoseRunAfterIsInTheFuture() {
        Long id = queue.enqueue(uniqueType(), null, null, "{}", null);
        // Push it into the future.
        IngestJob job = repo.findById(id).orElseThrow();
        job.setRunAfter(Instant.now().plusSeconds(3600));
        repo.saveAndFlush(job);

        List<IngestJob> claimed = queue.claim("worker-A", 10);
        assertTrue(claimed.stream().noneMatch(j -> j.getId().equals(id)),
                "a not-yet-runnable job must not be claimed");
    }

    // --------------------------------------------------------------- fail/backoff

    @Test
    void failIncrementsAttemptsAndSchedulesBackoff() {
        Long id = queue.enqueue(uniqueType(), null, null, "{}", null);
        queue.claim("w", 10); // RUNNING
        Instant before = Instant.now();

        queue.fail(id, "boom");

        IngestJob job = repo.findById(id).orElseThrow();
        assertEquals(IngestJobStatus.PENDING, job.getStatus(), "retry left → back to PENDING");
        assertEquals(1, job.getAttempts());
        assertEquals("boom", job.getLastError());
        assertNull(job.getLockedBy());
        assertTrue(job.getRunAfter().isAfter(before), "run_after must be pushed into the future (backoff)");
    }

    @Test
    void exceedingMaxAttemptsDeadLetters() {
        Long id = queue.enqueue(uniqueType(), null, null, "{}", null);
        IngestJob job = repo.findById(id).orElseThrow();
        job.setMaxAttempts(3);
        repo.saveAndFlush(job);

        queue.fail(id, "e1");
        queue.fail(id, "e2");
        queue.fail(id, "e3"); // attempts == max → DEAD

        IngestJob dead = repo.findById(id).orElseThrow();
        assertEquals(IngestJobStatus.DEAD, dead.getStatus());
        assertEquals(3, dead.getAttempts());
        assertEquals("e3", dead.getLastError());
    }

    @Test
    void backoffIsMonotonicNonDecreasingAndCapped() {
        long b1 = queue.backoffMillis(1);
        long b2 = queue.backoffMillis(2);
        long b3 = queue.backoffMillis(3);
        assertTrue(b1 >= 1000, "first backoff ≈ base");
        assertTrue(b2 >= b1, "backoff grows");
        assertTrue(b3 >= b2, "backoff grows");
        // Way past the cap it must be bounded (cap 60s + up to 10% jitter in test profile).
        assertTrue(queue.backoffMillis(40) <= 60000 + 6000, "backoff is capped");
    }

    // --------------------------------------------------------------- worker/process

    @Test
    void registeredHandlerProcessesJobToDone() {
        testHandler.reset();
        Long id = queue.enqueue(RecordingTestHandler.JOB_TYPE, null, 5L, "{\"ping\":true}", null);
        IngestJob claimed = queue.claim("w", 10).stream()
                .filter(j -> j.getId().equals(id)).findFirst().orElseThrow();

        worker.process(claimed);

        assertEquals(1, testHandler.count());
        assertEquals(IngestJobStatus.DONE, repo.findById(id).orElseThrow().getStatus());
    }

    @Test
    void unknownJobTypeDeadLetters() {
        Long id = queue.enqueue("NO_SUCH_HANDLER_TYPE", null, null, "{}", null);
        IngestJob claimed = queue.claim("w", 10).stream()
                .filter(j -> j.getId().equals(id)).findFirst().orElseThrow();

        worker.process(claimed);

        IngestJob job = repo.findById(id).orElseThrow();
        assertEquals(IngestJobStatus.DEAD, job.getStatus(), "no handler → permanent dead-letter");
        assertTrue(job.getLastError().contains("No JobHandler"));
    }

    @Test
    void throwingHandlerFailsAndReschedules() {
        FailingTestHandler.SHOULD_THROW.set(true);
        Long id = queue.enqueue(FailingTestHandler.JOB_TYPE, null, null, "{}", null);
        IngestJob claimed = queue.claim("w", 10).stream()
                .filter(j -> j.getId().equals(id)).findFirst().orElseThrow();

        worker.process(claimed);

        IngestJob job = repo.findById(id).orElseThrow();
        assertEquals(IngestJobStatus.PENDING, job.getStatus());
        assertEquals(1, job.getAttempts());
        assertTrue(job.getLastError().contains("IllegalStateException"));
    }

    // ------------------------------------------------------------------- reapStuck

    @Test
    void reapStuckResetsStaleRunningJob() {
        Long id = queue.enqueue(uniqueType(), null, null, "{}", null);
        // Simulate a crashed worker: RUNNING with an ancient lock.
        IngestJob job = repo.findById(id).orElseThrow();
        job.setStatus(IngestJobStatus.RUNNING);
        job.setLockedBy("dead-worker");
        job.setLockedAt(Instant.now().minusSeconds(3600));
        repo.saveAndFlush(job);

        int reaped = queue.reapStuck();
        assertTrue(reaped >= 1);

        IngestJob recovered = repo.findById(id).orElseThrow();
        assertEquals(IngestJobStatus.PENDING, recovered.getStatus());
        assertNull(recovered.getLockedBy());
        assertNull(recovered.getLockedAt());
    }

    @Test
    void handlersAreDiscoveredAsBeans() {
        assertTrue(worker.registeredJobTypes().contains(RecordingTestHandler.JOB_TYPE));
        assertTrue(worker.registeredJobTypes().contains(FailingTestHandler.JOB_TYPE));
    }
}
