package com.mayoclone.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A printable invoice for one IRCTC order.
 *
 * <p>Tax is GST on food at {@code taxRatePct} = 5%. {@code subTotal} is the
 * source of truth for the amount: if the order carries an explicit total
 * ({@code order.amount}) it is used as the subtotal; otherwise the sum of the
 * line items is used. {@code taxAmount} = 5% of subTotal, {@code total} =
 * subTotal + taxAmount.
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
            LocalDate deliveryDate,
            String deliverySlot,
            String aggregatorName
    ) {
    }

    public record Line(String name, int qty, BigDecimal price, BigDecimal lineTotal) {
    }
}
