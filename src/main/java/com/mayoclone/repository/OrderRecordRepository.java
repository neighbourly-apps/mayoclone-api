package com.mayoclone.repository;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {

    List<OrderRecord> findAllByOrderByPlacedAtDesc();

    List<OrderRecord> findByAggregatorOrderByPlacedAtDesc(Aggregator aggregator);

    boolean existsBySourceMessageId(String sourceMessageId);

    boolean existsByAggregatorAndExternalOrderId(Aggregator aggregator, String externalOrderId);

    long countByAggregator(Aggregator aggregator);
}
