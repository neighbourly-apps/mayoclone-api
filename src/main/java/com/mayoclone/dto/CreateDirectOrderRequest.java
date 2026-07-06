package com.mayoclone.dto;

import com.mayoclone.domain.PaymentMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Body for {@code POST /api/orders/direct} — a manually-entered walk-in / phone
 * order. Has no aggregator; amount defaults to the sum of the line items when omitted.
 */
public record CreateDirectOrderRequest(
        @NotBlank @Size(max = 255) String trainNumber,
        @Size(max = 255) String trainName,
        @Size(max = 255) String pnr,
        @Size(max = 255) String coach,
        @Size(max = 255) String berth,
        @NotBlank @Size(max = 255) String deliveryStationCode,
        @Size(max = 255) String deliveryStationName,
        @NotBlank @Size(max = 255) String passengerName,
        @NotBlank @Size(max = 255) String passengerPhone,
        @NotNull LocalDate deliveryDate,
        @Size(max = 255) String deliverySlot,
        @NotNull PaymentMode paymentMode,
        @NotEmpty @Valid List<OrderItemDto> items,
        BigDecimal amount
) {
}
