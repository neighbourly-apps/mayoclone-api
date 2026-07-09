package com.mayoclone.jobs;

import com.mayoclone.domain.IngestJob;
import com.mayoclone.domain.IngestJobStatus;
import com.mayoclone.repository.IngestJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Durable background job queue. The persistence model is {@link IngestJob} /
 * {@code ingest_job} (Flyway V10).
 *
 * <p><b>Multi-instance safety.</b> {@link #claim} runs a native
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} on Postgres so concurrent workers —
 * across app instances — never grab the same row. On H2 (test create-drop) the
 * {@code SKIP LOCKED} suffix is omitted; the H2 suites drive the queue
 * single-threaded, and true concurrent SKIP LOCKED is proven in the Docker-gated
 * Postgres suite.
 *
 * <p><b>Retry / dead-letter.</b> {@link #fail} increments {@code attempts} and either
 * re-schedules the job to PENDING with exponential backoff + jitter, or — once
 * {@code attempts >= max_attempts} (or on a permanent failure) — dead-letters it to
 * {@code DEAD}.
 */
@Service
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    /** Active statuses a dedup_key coalesces against. */
    private static final List<IngestJobStatus> ACTIVE =
            List.of(IngestJobStatus.PENDING, IngestJobStatus.RUNNING);

    private static final int MAX_ERROR_LEN = 4000;

    private final IngestJobRepository repo;
    private final JdbcTemplate jdbc;
    private final JobMetrics metrics;

    private final int defaultMaxAttempts;
    private final long backoffBaseMs;
    private final long backoffCapMs;
    private final Duration stuckTimeout;

    /** Resolved once: true when the datasource is Postgres (supports SKIP LOCKED). */
    private volatile Boolean postgres;

    public JobQueue(IngestJobRepository repo,
                    JdbcTemplate jdbc,
                    JobMetrics metrics,
                    @Value("${mayoclone.jobs.max-attempts:8}") int defaultMaxAttempts,
                    @Value("${mayoclone.jobs.backoff-base-ms:2000}") long backoffBaseMs,
                    @Value("${mayoclone.jobs.backoff-cap-ms:300000}") long backoffCapMs,
                    @Value("${mayoclone.jobs.stuck-timeout-ms:300000}") long stuckTimeoutMs) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.defaultMaxAttempts = defaultMaxAttempts;
        this.backoffBaseMs = backoffBaseMs;
        this.backoffCapMs = backoffCapMs;
        this.stuckTimeout = Duration.ofMillis(stuckTimeoutMs);
    }

    // ------------------------------------------------------------------ enqueue

    /**
     * Enqueue a runnable job (PENDING, {@code run_after = now}). When {@code dedupKey}
     * is non-null and an active (PENDING/RUNNING) job already exists for it, the
     * existing job is COALESCED — its payload is refreshed to the newest and its
     * {@code run_after} bumped to now — rather than inserting a duplicate. Returns the
     * (new or coalesced) job id.
     */
    @Transactional
    public Long enqueue(String jobType, Long accountId, Long vendorId, String payload, String dedupKey) {
        if (dedupKey != null && !dedupKey.isBlank()) {
            Optional<IngestJob> existing =
                    repo.findFirstByDedupKeyAndStatusInOrderByIdAsc(dedupKey, ACTIVE);
            if (existing.isPresent()) {
                return coalesce(existing.get(), payload);
            }
        }
        try {
            return insertNew(jobType, accountId, vendorId, payload, dedupKey);
        } catch (DataIntegrityViolationException race) {
            // Lost a race against a concurrent enqueue for the same dedup_key (the
            // partial-unique index fired). Coalesce onto the winner instead.
            if (dedupKey != null && !dedupKey.isBlank()) {
                Optional<IngestJob> existing =
                        repo.findFirstByDedupKeyAndStatusInOrderByIdAsc(dedupKey, ACTIVE);
                if (existing.isPresent()) {
                    return coalesce(existing.get(), payload);
                }
            }
            throw race;
        }
    }

    private Long insertNew(String jobType, Long accountId, Long vendorId, String payload, String dedupKey) {
        Instant now = Instant.now();
        IngestJob job = new IngestJob();
        job.setJobType(jobType);
        job.setAccountId(accountId);
        job.setVendorId(vendorId);
        job.setPayload(payload);
        job.setDedupKey(dedupKey);
        job.setStatus(IngestJobStatus.PENDING);
        job.setAttempts(0);
        job.setMaxAttempts(defaultMaxAttempts);
        job.setRunAfter(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        Long id = repo.saveAndFlush(job).getId();
        metrics.enqueued(jobType);
        return id;
    }

    private Long coalesce(IngestJob existing, String payload) {
        Instant now = Instant.now();
        existing.setPayload(payload);
        existing.setRunAfter(now);
        existing.setUpdatedAt(now);
        repo.save(existing);
        metrics.enqueued(existing.getJobType());
        return existing.getId();
    }

    // -------------------------------------------------------------------- claim

    /**
     * Atomically claim up to {@code batchSize} runnable jobs for {@code workerId},
     * flipping them PENDING → RUNNING with {@code locked_by}/{@code locked_at} set.
     * Safe across instances via {@code FOR UPDATE SKIP LOCKED} (Postgres).
     */
    @Transactional
    public List<IngestJob> claim(String workerId, int batchSize) {
        if (batchSize <= 0) {
            return List.of();
        }
        String sql = "SELECT id FROM ingest_job WHERE status = 'PENDING' AND run_after <= now() "
                + "ORDER BY run_after LIMIT " + batchSize + lockClause();
        List<Long> ids = jdbc.queryForList(sql, Long.class);
        if (ids.isEmpty()) {
            return List.of();
        }
        for (Long id : ids) {
            jdbc.update("UPDATE ingest_job SET status = 'RUNNING', locked_by = ?, "
                    + "locked_at = now(), updated_at = now() WHERE id = ?", workerId, id);
        }
        // Re-materialise the claimed rows (same transaction → sees the RUNNING state).
        return repo.findAllById(ids);
    }

    /** {@code " FOR UPDATE SKIP LOCKED"} on Postgres; empty on H2 (single-threaded tests). */
    private String lockClause() {
        return isPostgres() ? " FOR UPDATE SKIP LOCKED" : "";
    }

    private boolean isPostgres() {
        Boolean pg = this.postgres;
        if (pg == null) {
            pg = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) conn -> {
                DatabaseMetaData md = conn.getMetaData();
                String name = md.getDatabaseProductName();
                return name != null && name.toLowerCase().contains("postgres");
            }));
            this.postgres = pg;
        }
        return pg;
    }

    // ------------------------------------------------------------ complete/fail

    /** Mark a job DONE (terminal success). */
    @Transactional
    public void complete(Long jobId) {
        IngestJob job = repo.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        Instant now = Instant.now();
        job.setStatus(IngestJobStatus.DONE);
        job.setLockedBy(null);
        job.setLockedAt(null);
        job.setUpdatedAt(now);
        repo.save(job);
        metrics.processed(job.getJobType(), JobMetrics.RESULT_DONE);
    }

    /**
     * Record a failed attempt. Increments {@code attempts}; if the budget is exhausted
     * the job dead-letters to DEAD, otherwise it is re-scheduled to PENDING with an
     * exponential backoff + jitter {@code run_after}.
     */
    @Transactional
    public void fail(Long jobId, String error) {
        fail(jobId, error, false);
    }

    /**
     * As {@link #fail(Long, String)} but {@code permanent=true} dead-letters
     * immediately regardless of remaining attempts (e.g. no handler for the job type).
     */
    @Transactional
    public void fail(Long jobId, String error, boolean permanent) {
        IngestJob job = repo.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        Instant now = Instant.now();
        int attempts = job.getAttempts() + 1;
        job.setAttempts(attempts);
        job.setLastError(truncate(error));
        job.setLockedBy(null);
        job.setLockedAt(null);
        job.setUpdatedAt(now);

        if (permanent || attempts >= job.getMaxAttempts()) {
            job.setStatus(IngestJobStatus.DEAD);
            repo.save(job);
            metrics.processed(job.getJobType(), JobMetrics.RESULT_DEAD);
            log.warn("Job dead-lettered: id={} type={} attempts={}/{} lastError={}",
                    job.getId(), job.getJobType(), attempts, job.getMaxAttempts(), job.getLastError());
        } else {
            job.setStatus(IngestJobStatus.PENDING);
            job.setRunAfter(now.plusMillis(backoffMillis(attempts)));
            repo.save(job);
            metrics.processed(job.getJobType(), JobMetrics.RESULT_FAILED);
        }
    }

    /** Exponential backoff for the Nth attempt: min(base * 2^(n-1), cap) + up-to-10% jitter. */
    long backoffMillis(int attempt) {
        int shift = Math.min(attempt - 1, 30); // guard against overflow
        long exp = backoffBaseMs << shift;
        if (exp < 0 || exp > backoffCapMs) {
            exp = backoffCapMs;
        }
        long jitter = exp <= 0 ? 0 : ThreadLocalRandom.current().nextLong(0, Math.max(1, exp / 10));
        return exp + jitter;
    }

    // -------------------------------------------------------------------- reap

    /**
     * Crash recovery: reset jobs stuck in RUNNING (locked_at older than the configured
     * stuck-timeout) back to PENDING so a healthy worker can pick them up. Returns the
     * number of rows reaped.
     */
    @Transactional
    public int reapStuck() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(stuckTimeout);
        int reaped = repo.reapStuck(cutoff, now);
        if (reaped > 0) {
            log.warn("Reaped {} stuck RUNNING job(s) older than {} back to PENDING", reaped, stuckTimeout);
        }
        return reaped;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }
}
