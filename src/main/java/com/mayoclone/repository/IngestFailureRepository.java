package com.mayoclone.repository;

import com.mayoclone.domain.IngestFailure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IngestFailureRepository extends JpaRepository<IngestFailure, Long> {

    // ---- Tenant-scoped access (all request-driven paths MUST use these) ----

    List<IngestFailure> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    Optional<IngestFailure> findByIdAndAccountId(Long id, Long accountId);

    boolean existsByIdAndAccountId(Long id, Long accountId);
}
