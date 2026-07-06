package com.mayoclone.service;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderRecord;
import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.OrderStatsDto;
import com.mayoclone.repository.OrderRecordRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-side queries and aggregate stats over stored orders. */
@Service
public class OrderService {

    private final OrderRecordRepository orderRepo;

    public OrderService(OrderRecordRepository orderRepo) {
        this.orderRepo = orderRepo;
    }

    /** All orders (optionally filtered by aggregator), newest first. */
    public List<OrderDto> list(Aggregator aggregator) {
        List<OrderRecord> records = (aggregator == null)
                ? orderRepo.findAllByOrderByPlacedAtDesc()
                : orderRepo.findByAggregatorOrderByPlacedAtDesc(aggregator);
        return records.stream().map(OrderDto::from).toList();
    }

    public OrderStatsDto stats() {
        List<OrderRecord> all = orderRepo.findAll();
        BigDecimal revenue = all.stream()
                .map(o -> o.getAmount() != null ? o.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Preserve enum declaration order and always include every aggregator (even zero).
        Map<String, Long> byAggregator = new LinkedHashMap<>();
        for (Aggregator agg : Aggregator.values()) {
            byAggregator.put(agg.name(), orderRepo.countByAggregator(agg));
        }
        return new OrderStatsDto(all.size(), revenue, byAggregator);
    }
}
