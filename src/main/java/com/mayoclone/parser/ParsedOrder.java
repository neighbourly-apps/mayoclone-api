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
        String sourceMessageId
) {
}
