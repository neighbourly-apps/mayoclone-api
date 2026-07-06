package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Immutable result of parsing a single aggregator order email. */
public record ParsedOrder(
        Aggregator aggregator,
        String externalOrderId,
        String customerName,
        BigDecimal amount,
        String currency,
        Instant placedAt,
        String status,
        List<OrderItem> items,
        String subject,
        String sourceMessageId
) {
}
