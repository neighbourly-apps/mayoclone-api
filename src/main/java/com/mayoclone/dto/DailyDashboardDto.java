package com.mayoclone.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Daily business summary for one delivery date, scoped to a tenant.
 *
 * @param date         the delivery date summarised
 * @param total        total order count for the day
 * @param online       PREPAID bucket (count + revenue)
 * @param cod          COD bucket (count + revenue)
 * @param byStatus     count per {@code OrderStatus} name (every status present, 0 when none)
 * @param undelivered  count of orders in CANCELLED (kept as {@code undelivered} for
 *                     API stability under the 5-status model)
 * @param unassigned   count of orders with no rider assigned
 * @param byAggregator per-aggregator counts (DIRECT orders, having no aggregator, are excluded)
 */
public record DailyDashboardDto(
        LocalDate date,
        long total,
        Bucket online,
        Bucket cod,
        Map<String, Long> byStatus,
        long undelivered,
        long unassigned,
        List<AggregatorCount> byAggregator
) {

    /** Count + revenue for a payment bucket. */
    public record Bucket(long count, BigDecimal revenue) {
    }

    /** One aggregator's order count for the day. */
    public record AggregatorCount(String code, String name, long count) {
    }
}
