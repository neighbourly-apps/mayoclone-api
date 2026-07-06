package com.mayoclone.dto;

import com.mayoclone.domain.IrctcOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Full read view of an IRCTC order, with a nested aggregator summary. */
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
        String status,
        List<OrderItemDto> items,
        String subject,
        Instant placedAt,
        Instant createdAt
) {

    public static OrderDto from(IrctcOrder o) {
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
                o.getStatus(),
                o.getItems().stream().map(OrderItemDto::from).toList(),
                o.getSubject(),
                o.getPlacedAt(),
                o.getCreatedAt()
        );
    }
}
