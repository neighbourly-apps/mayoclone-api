package com.mayoclone.dto;

import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderStatusEvent;

import java.time.Instant;

/** One entry in an order's {@code statusHistory} timeline. */
public record StatusEventDto(OrderStatus status, Instant at) {

    public static StatusEventDto from(OrderStatusEvent e) {
        return new StatusEventDto(e.getStatus(), e.getAt());
    }
}
