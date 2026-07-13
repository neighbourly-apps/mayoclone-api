package com.mayoclone.dto;

import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.PaymentMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Full read view of an IRCTC order. The nested {@link AggregatorSummaryDto} is
 * {@code null} for DIRECT (walk-in/phone) orders. Lifecycle fields (status,
 * rider, timestamps) and the {@code statusHistory} timeline are included.
 */
public record OrderDto(
        Long id,
        AggregatorSummaryDto aggregator,
        Long vendorId,
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
        BigDecimal amountToCollect,
        BigDecimal subtotalAmount,
        BigDecimal gstAmount,
        BigDecimal deliveryFee,
        BigDecimal discountAmount,
        OrderType orderType,
        PaymentMode paymentMode,
        OrderStatus status,
        Long riderId,
        String riderName,
        Instant assignedAt,
        Instant deliveredAt,
        String cancellationReason,
        boolean needsReview,
        String reviewReason,
        List<OrderItemDto> items,
        List<StatusEventDto> statusHistory,
        String subject,
        Instant placedAt,
        Instant createdAt
) {

    /** Build a DTO with the rider name and status timeline already resolved. */
    public static OrderDto from(IrctcOrder o, String riderName, List<StatusEventDto> statusHistory) {
        return new OrderDto(
                o.getId(),
                AggregatorSummaryDto.from(o.getAggregator()),
                o.getVendorId(),
                o.getExternalOrderId(),
                o.getPnr(),
                o.getTrainNumber(),
                o.getTrainName(),
                o.getCoach(),
                o.getBerth(),
                o.getBoardingStationCode(),
                o.getDeliveryStationCode(),
                o.getDeliveryStationName(),
                o.getPassengerName(),
                o.getPassengerPhone(),
                o.getDeliveryDate(),
                o.getDeliverySlot(),
                o.getAmount(),
                o.getCurrency(),
                o.getAmountToCollect(),
                o.getSubtotalAmount(),
                o.getGstAmount(),
                o.getDeliveryFee(),
                o.getDiscountAmount(),
                o.getOrderType(),
                o.getPaymentMode(),
                o.getStatus(),
                o.getRiderId(),
                riderName,
                o.getAssignedAt(),
                o.getDeliveredAt(),
                o.getCancellationReason(),
                o.isNeedsReview(),
                o.getReviewReason(),
                o.getItems().stream().map(OrderItemDto::from).toList(),
                statusHistory,
                o.getSubject(),
                o.getPlacedAt(),
                o.getCreatedAt()
        );
    }
}
