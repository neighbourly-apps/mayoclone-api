package com.mayoclone.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped reporting/analytics summary over a delivery-date window.
 *
 * <p>Convention: order <em>counts</em> include orders of every status; monetary
 * <em>revenue</em> everywhere EXCLUDES CANCELLED orders. {@code topItems} is
 * likewise built only from non-cancelled orders.
 */
public record ReportSummaryDto(
        LocalDate from,
        LocalDate to,
        Totals totals,
        PaymentSplit paymentSplit,
        Map<String, Long> statusCounts,
        double fulfillmentRate,
        Double avgDeliveryMinutes,
        List<AggregatorBucket> byAggregator,
        List<StationBucket> byStation,
        List<DayBucket> byDay,
        List<HourBucket> byHour,
        List<TrainBucket> topTrains,
        List<ItemBucket> topItems
) {

    public record Totals(long orders, BigDecimal revenue) {
    }

    public record Bucket(long count, BigDecimal revenue) {
    }

    public record PaymentSplit(Bucket online, Bucket cod) {
    }

    public record AggregatorBucket(String code, String name, long orders, BigDecimal revenue) {
    }

    public record StationBucket(String code, String name, long orders, BigDecimal revenue) {
    }

    public record DayBucket(LocalDate date, long orders, BigDecimal revenue) {
    }

    /** Order/revenue distribution across the 24 hours of the day (placedAt, server zone). */
    public record HourBucket(int hour, long orders, BigDecimal revenue) {
    }

    public record TrainBucket(String trainNumber, String trainName, long orders) {
    }

    public record ItemBucket(String name, long qty, BigDecimal revenue) {
    }
}
