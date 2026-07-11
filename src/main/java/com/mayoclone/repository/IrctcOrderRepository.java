package com.mayoclone.repository;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IrctcOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IrctcOrderRepository extends JpaRepository<IrctcOrder, Long> {

    // ---- Tenant-scoped reads (all request-driven paths MUST use these) ----

    List<IrctcOrder> findByAccountIdOrderByPlacedAtDesc(Long accountId);

    Optional<IrctcOrder> findByIdAndAccountId(Long id, Long accountId);

    /** A rider's orders whose delivery date falls in [from, to] inclusive (SLA/performance). */
    List<IrctcOrder> findByAccountIdAndRiderIdAndDeliveryDateBetween(
            Long accountId, Long riderId, LocalDate from, LocalDate to);

    /**
     * Tenant-scoped, case-insensitive global search. Matches the given lowered,
     * {@code %}-wrapped term against externalOrderId, pnr, passengerPhone,
     * passengerName, trainNumber and deliveryStationCode. Newest first; caller
     * bounds the row count via {@code pageable}.
     */
    @Query("""
            select o from IrctcOrder o
            where o.accountId = :accountId and (
                  lower(o.externalOrderId)     like :q
               or lower(o.pnr)                 like :q
               or lower(o.passengerPhone)      like :q
               or lower(o.passengerName)       like :q
               or lower(o.trainNumber)         like :q
               or lower(o.deliveryStationCode) like :q
            )
            order by o.placedAt desc
            """)
    List<IrctcOrder> search(@Param("accountId") Long accountId,
                            @Param("q") String q,
                            Pageable pageable);

    long countByAccountIdAndAggregator(Long accountId, Aggregator aggregator);

    long countByAccountIdAndDeliveryDate(Long accountId, LocalDate deliveryDate);

    /** All of a tenant's orders for one delivery date (feeds the daily dashboard). */
    List<IrctcOrder> findByAccountIdAndDeliveryDate(Long accountId, LocalDate deliveryDate);

    /**
     * A tenant's orders whose delivery date falls in [from, to] inclusive.
     * Feeds settlement/reconciliation and the reporting/analytics summary.
     */
    List<IrctcOrder> findByAccountIdAndDeliveryDateBetween(Long accountId, LocalDate from, LocalDate to);

    // ---- Platform super-admin: cross-tenant counts ----

    /** Order count for one account (admin detail view). */
    long countByAccountId(Long accountId);

    /**
     * Batched order count per account for the admin list view (avoids N+1).
     * Each row is {@code [accountId(Long), count(Long)]}.
     */
    @Query("select o.accountId, count(o) from IrctcOrder o where o.accountId in :ids group by o.accountId")
    List<Object[]> countByAccountIds(@Param("ids") List<Long> ids);

    // ---- Dedup (global: an aggregator's external order id is globally unique) ----

    boolean existsBySourceMessageId(String sourceMessageId);

    boolean existsByAggregatorAndExternalOrderId(Aggregator aggregator, String externalOrderId);
}
