package com.mayoclone.repository;

import com.mayoclone.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only audit sink. The app only ever inserts; Postgres enforces
 * immutability with an UPDATE/DELETE trigger (see the audit migration).
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
