package com.mayoclone.parser;

import com.mayoclone.domain.OrderItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Immutable result of parsing a single IRCTC e-catering order email. */
public record ParsedOrder(
        String aggregatorCode,
        String externalOrderId,
        String pnr,
        String trainNumber,
        String trainName,
        String coach,
        String berth,
        String boardingStationCode,
        String deliveryStationCode,
        String deliveryStationName,
        String passengerName,
        String passengerPhone,
        LocalDate deliveryDate,
        String deliverySlot,
        BigDecimal amount,
        String currency,
        String status,
        List<OrderItem> items,
        String subject,
        String sourceMessageId,
        /** "COD" | "PREPAID" | "PAID" | null when the email gives no signal. */
        String paymentMode,
        /** Cash to collect on delivery (₹0 for fully-prepaid); null when unknown. */
        BigDecimal amountToCollect,
        /** Bill breakdown, each nullable when the email gives no such line. {@link #amount} stays the grand total. */
        BigDecimal subtotalAmount,
        BigDecimal gstAmount,
        BigDecimal deliveryFee,
        BigDecimal discountAmount
) {
}
