package com.mayoclone.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aggregate order statistics.
 *
 * @param total         total number of orders
 * @param totalRevenue  sum of all order amounts
 * @param byAggregator  order counts keyed by aggregator name (ZOMATO/SWIGGY/UBER_EATS)
 */
public record OrderStatsDto(long total, BigDecimal totalRevenue, Map<String, Long> byAggregator) {
}
