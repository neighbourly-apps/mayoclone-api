package com.mayoclone.repository;

import com.mayoclone.domain.SubscriptionPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    boolean existsByProviderAndProviderPaymentId(String provider, String providerPaymentId);

    // ---- Platform super-admin ----

    /** All payments for one account, newest first (detail view). */
    List<SubscriptionPayment> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    /** Total revenue across ALL accounts in minor units (paise). */
    @Query("select coalesce(sum(p.amount), 0) from SubscriptionPayment p")
    long totalRevenuePaise();

    /** Number of distinct accounts that have ever paid. */
    @Query("select count(distinct p.accountId) from SubscriptionPayment p")
    long payingAccountCount();

    /**
     * Batched aggregate per account for the list view (avoids N+1). Each row is
     * {@code [accountId(Long), count(Long), maxCreatedAt(Instant), sumAmount(Long)]}.
     */
    @Query("select p.accountId, count(p), max(p.createdAt), coalesce(sum(p.amount), 0) "
            + "from SubscriptionPayment p where p.accountId in :ids group by p.accountId")
    List<Object[]> aggregateByAccountIds(@Param("ids") List<Long> ids);
}
