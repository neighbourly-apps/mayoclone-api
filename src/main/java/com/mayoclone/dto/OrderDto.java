package com.mayoclone.dto;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderDto(
        Long id,
        Aggregator aggregator,
        String externalOrderId,
        String customerName,
        BigDecimal amount,
        String currency,
        List<OrderItemDto> items,
        Instant placedAt,
        String status,
        String subject,
        Instant createdAt
) {

    public static OrderDto from(OrderRecord o) {
        return new OrderDto(
                o.getId(),
                o.getAggregator(),
                o.getExternalOrderId(),
                o.getCustomerName(),
                o.getAmount(),
                o.getCurrency(),
                o.getItems().stream().map(OrderItemDto::from).toList(),
                o.getPlacedAt(),
                o.getStatus(),
                o.getSubject(),
                o.getCreatedAt()
        );
    }
}
