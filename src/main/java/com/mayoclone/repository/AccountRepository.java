package com.mayoclone.repository;

import com.mayoclone.domain.Account;
import com.mayoclone.domain.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /**
     * MRR support: {@code [plan, count]} rows for ACTIVE (paid) tenant accounts,
     * grouped by their stored plan code (a null plan groups as one bucket). Callers
     * translate each plan to its monthly-equivalent amount. Excludes SUPER_ADMIN.
     */
    @Query("select a.plan as plan, count(a) as cnt from Account a "
            + "where a.role <> :role and a.subscriptionStatus = :status group by a.plan")
    List<Object[]> countActiveByPlan(@Param("role") String role,
                                     @Param("status") SubscriptionStatus status);

    /**
     * Renewal-reminder sweep: tenant accounts (excludes SUPER_ADMIN) that have NOT
     * been reminded for the current period ({@code renewalReminderSentAt is null}) and
     * whose access lapses within the window (now, cutoff] — either a live PAID period
     * (ACTIVE, {@code currentPeriodEnd}) or a live free trial (TRIALING, {@code trialEndsAt}).
     * Paged/capped by the caller.
     */
    @Query("select a from Account a where a.role <> :role and a.renewalReminderSentAt is null and ("
            + "(a.subscriptionStatus = :active and a.currentPeriodEnd is not null "
            + "  and a.currentPeriodEnd > :now and a.currentPeriodEnd <= :cutoff) or "
            + "(a.subscriptionStatus = :trialing and a.trialEndsAt is not null "
            + "  and a.trialEndsAt > :now and a.trialEndsAt <= :cutoff))")
    List<Account> findDueForRenewalReminder(@Param("role") String role,
                                            @Param("active") SubscriptionStatus active,
                                            @Param("trialing") SubscriptionStatus trialing,
                                            @Param("now") Instant now,
                                            @Param("cutoff") Instant cutoff,
                                            Pageable pageable);
}
