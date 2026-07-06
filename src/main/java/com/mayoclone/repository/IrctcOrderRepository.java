package com.mayoclone.repository;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IrctcOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface IrctcOrderRepository extends JpaRepository<IrctcOrder, Long> {

    List<IrctcOrder> findAllByOrderByPlacedAtDesc();

    boolean existsBySourceMessageId(String sourceMessageId);

    boolean existsByAggregatorAndExternalOrderId(Aggregator aggregator, String externalOrderId);

    long countByAggregator(Aggregator aggregator);

    long countByDeliveryDate(LocalDate deliveryDate);
}
