package com.mayoclone.repository;

import com.mayoclone.domain.Account;
import com.mayoclone.domain.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    // ---- Platform super-admin: cross-tenant reads ----

    /**
     * Paginated, cross-tenant account search for the admin panel. {@code q} (already
     * lowered + {@code %}-wrapped by the caller, or null) filters email/businessName;
     * {@code status} (or null) filters by subscription status. Callers pass a Pageable
     * sorted by {@code createdAt} desc.
     */
    @Query("select a from Account a where "
            + "(:q is null or lower(a.email) like :q or lower(a.businessName) like :q) "
            + "and (:status is null or a.subscriptionStatus = :status)")
    Page<Account> adminSearch(@Param("q") String q,
                              @Param("status") SubscriptionStatus status,
                              Pageable pageable);

    /** Count of tenant accounts (excludes SUPER_ADMIN) with the given subscription status. */
    long countByRoleNotAndSubscriptionStatus(String role, SubscriptionStatus status);

    /** Count of all tenant accounts (excludes SUPER_ADMIN). */
    long countByRoleNot(String role);

    /** Count of tenant accounts (excludes SUPER_ADMIN) created after the given instant. */
    long countByRoleNotAndCreatedAtAfter(String role, Instant since);
}
