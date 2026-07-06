package com.mayoclone.dto;

import com.mayoclone.domain.OrderItem;

import java.math.BigDecimal;

public record OrderItemDto(String name, int qty, BigDecimal price) {

    public static OrderItemDto from(OrderItem item) {
        return new OrderItemDto(item.getName(), item.getQty(), item.getPrice());
    }
}
