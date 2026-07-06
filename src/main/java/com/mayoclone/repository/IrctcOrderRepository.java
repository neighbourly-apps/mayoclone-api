package com.mayoclone.repository;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IrctcOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IrctcOrderRepository extends JpaRepository<IrctcOrder, Long> {

    // ---- Tenant-scoped reads (all request-driven paths MUST use these) ----

    List<IrctcOrder> findByAccountIdOrderByPlacedAtDesc(Long accountId);

    Optional<IrctcOrder> findByIdAndAccountId(Long id, Long accountId);

    long countByAccountIdAndAggregator(Long accountId, Aggregator aggregator);

    long countByAccountIdAndDeliveryDate(Long accountId, LocalDate deliveryDate);

    // ---- Dedup (global: an aggregator's external order id is globally unique) ----

    boolean existsBySourceMessageId(String sourceMessageId);

    boolean existsByAggregatorAndExternalOrderId(Aggregator aggregator, String externalOrderId);
}
