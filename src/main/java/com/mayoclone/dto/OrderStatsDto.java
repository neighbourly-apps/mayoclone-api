package com.mayoclone.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregate order statistics for the dashboard.
 *
 * @param total         total number of orders
 * @param totalRevenue  sum of all order amounts
 * @param byAggregator  per-aggregator counts (from the managed aggregator table)
 * @param upcomingToday orders whose deliveryDate is today
 */
public record OrderStatsDto(
        long total,
        BigDecimal totalRevenue,
        List<AggregatorCount> byAggregator,
        long upcomingToday
) {

    /** One row of the {@code byAggregator} breakdown. */
    public record AggregatorCount(String code, String name, String brandColor, long count) {
    }
}
