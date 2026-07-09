package com.mayoclone.repository;

import com.mayoclone.domain.ProcessedPush;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * JPA access for the {@link ProcessedPush} idempotency ledger. Lookups use the
 * inherited {@code existsById(messageId)} / {@code save(...)}; the retention purge
 * removes rows older than a cutoff.
 */
public interface ProcessedPushRepository extends JpaRepository<ProcessedPush, String> {

    /** Retention purge: drop ledger rows older than {@code cutoff}. Returns the count removed. */
    @Transactional
    long deleteByReceivedAtBefore(Instant cutoff);
}
