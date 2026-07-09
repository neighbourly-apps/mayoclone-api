package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A durable, at-least-once background work item. Rows live in {@code ingest_job}
 * (Flyway V10) and are claimed by {@link com.mayoclone.jobs.JobQueue#claim} with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} so the queue is safe across multiple
 * app instances.
 *
 * <p>Timestamps are set explicitly by {@link com.mayoclone.jobs.JobQueue} (mirroring
 * the rest of the codebase) rather than via JPA callbacks, so the queue stays in
 * full control of {@code run_after} backoff scheduling.
 *
 * <p>The unique-per-active-dedup_key constraint (partial unique index) lives ONLY in
 * V10 (Postgres); it is intentionally NOT mapped here so H2 create-drop test schemas
 * stay simple and re-enqueue-after-DONE works. Hibernate {@code validate} does not
 * check indexes, so this is safe.
 */
@Entity
@Table(
        name = "ingest_job",
        indexes = {
                @Index(name = "idx_ingest_job_status_run_after", columnList = "status, run_after"),
                @Index(name = "idx_ingest_job_vendor", columnList = "vendor_id")
        }
)
public class IngestJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical work type, e.g. {@code GMAIL_HISTORY}. Routes the job to a {@code JobHandler}. */
    @Column(name = "job_type", nullable = false, length = 64)
    private String jobType;

    /** Owning tenant, when the work is tenant-scoped. Nullable. */
    @Column(name = "account_id")
    private Long accountId;

    /** Owning vendor/mailbox, when the work is vendor-scoped. Nullable. */
    @Column(name = "vendor_id")
    private Long vendorId;

    /** Small JSON blob, e.g. {@code {"historyId":"12345"}}. */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    /**
     * Coalescing key. When set, a burst of enqueues for the same key updates the
     * existing PENDING/RUNNING job instead of inserting duplicates.
     */
    @Column(name = "dedup_key", length = 255)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IngestJobStatus status = IngestJobStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 8;

    /** Earliest time the job may be claimed (backoff schedule). */
    @Column(name = "run_after", nullable = false)
    private Instant runAfter;

    /** Worker id that currently holds the job (RUNNING). */
    @Column(name = "locked_by", length = 128)
    private String lockedBy;

    /** When the job was claimed; used by reapStuck() for crash recovery. */
    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public IngestJob() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getVendorId() {
        return vendorId;
    }

    public void setVendorId(Long vendorId) {
        this.vendorId = vendorId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public IngestJobStatus getStatus() {
        return status;
    }

    public void setStatus(IngestJobStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Instant getRunAfter() {
        return runAfter;
    }

    public void setRunAfter(Instant runAfter) {
        this.runAfter = runAfter;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
