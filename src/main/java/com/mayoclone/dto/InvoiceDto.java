package com.mayoclone.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A printable invoice for one IRCTC order.
 *
 * <p>We NEVER compute tax ourselves. {@code subTotal}, {@code taxAmount} (the
 * aggregator's stated GST), {@code total}, and the per-line breakdown on
 * {@code order} are exactly the figures parsed from the aggregator email
 * ({@code taxRatePct} is null — no derived rate). Missing figures are null and
 * the UI simply omits those lines.
 */
public record InvoiceDto(
        String invoiceNumber,
        Instant issuedAt,
        Vendor vendor,
        Order order,
        List<Line> items,
        BigDecimal subTotal,
        BigDecimal taxRatePct,
        BigDecimal taxAmount,
        BigDecimal total,
        String currency
) {

    /** Nullable when the order has no owning vendor (e.g. demo orders). */
    public record Vendor(
            String restaurantName,
            String gstin,
            String addressLine,
            String stationCode,
            String phone
    ) {
    }

    public record Order(
            String externalOrderId,
            String pnr,
            String trainNumber,
            String trainName,
            String coach,
            String berth,
            String deliveryStationCode,
            String deliveryStationName,
            String passengerName,
            String passengerPhone,
            LocalDate deliveryDate,
            String deliverySlot,
            String aggregatorName,
            String paymentMode,
            // Real aggregator-stated figures — never computed by us. Nullable when the email
            // didn't state them; the UI shows only the lines that are present.
            BigDecimal amount,
            BigDecimal amountToCollect,
            BigDecimal subtotalAmount,
            BigDecimal gstAmount,
            BigDecimal deliveryFee,
            BigDecimal discountAmount
    ) {
    }

    public record Line(String name, String description, int qty, BigDecimal price, BigDecimal lineTotal) {
    }
}
