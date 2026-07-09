package com.mayoclone.repository;

import com.mayoclone.domain.IngestJob;
import com.mayoclone.domain.IngestJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * JPA access for {@link IngestJob}. The atomic multi-instance {@code claim()} uses a
 * native {@code FOR UPDATE SKIP LOCKED} query via JdbcTemplate (Postgres-only) and
 * lives in {@link com.mayoclone.jobs.JobQueue}; everything else is standard JPA.
 */
public interface IngestJobRepository extends JpaRepository<IngestJob, Long> {

    /** The single active (PENDING/RUNNING) job for a dedup key, if any — for coalescing. */
    Optional<IngestJob> findFirstByDedupKeyAndStatusInOrderByIdAsc(
            String dedupKey, Collection<IngestJobStatus> statuses);

    /** Queue-depth gauge source. */
    long countByStatus(IngestJobStatus status);

    /**
     * Crash recovery: reset jobs stuck in RUNNING whose lock is older than {@code cutoff}
     * back to PENDING so they become runnable again. Returns the number of rows reaped.
     */
    @Modifying
    @Query("update IngestJob j set j.status = com.mayoclone.domain.IngestJobStatus.PENDING, "
            + "j.lockedBy = null, j.lockedAt = null, j.runAfter = :now, j.updatedAt = :now "
            + "where j.status = com.mayoclone.domain.IngestJobStatus.RUNNING and j.lockedAt < :cutoff")
    int reapStuck(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
