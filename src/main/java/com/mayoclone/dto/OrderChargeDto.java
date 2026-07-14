package com.mayoclone.dto;

import com.mayoclone.domain.OrderCharge;

import java.math.BigDecimal;

/** One extra named aggregator bill line (e.g. "Gateway Platform Fees"). */
public record OrderChargeDto(String label, BigDecimal amount) {
    public static OrderChargeDto from(OrderCharge c) {
        return new OrderChargeDto(c.getLabel(), c.getAmount());
    }
}
