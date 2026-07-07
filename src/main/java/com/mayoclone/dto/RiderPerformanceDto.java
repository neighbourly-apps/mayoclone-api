package com.mayoclone.dto;

/**
 * Per-rider fulfilment / SLA metrics over a delivery-date window.
 *
 * <ul>
 *   <li>{@code assigned} — orders assigned to the rider whose deliveryDate is in range</li>
 *   <li>{@code onTimeRate} — delivered / (delivered + undelivered), 0..1 (null if neither)</li>
 *   <li>{@code avgDeliveryMinutes} — mean (deliveredAt - assignedAt) in minutes over
 *       delivered orders that carry both timestamps (null if none)</li>
 * </ul>
 */
public record RiderPerformanceDto(
        Long riderId,
        String name,
        long assigned,
        long delivered,
        long undelivered,
        Double onTimeRate,
        Double avgDeliveryMinutes
) {
}
