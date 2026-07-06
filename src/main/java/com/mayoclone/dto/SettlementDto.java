package com.mayoclone.dto;

import com.mayoclone.domain.Settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** View of a persisted settlement snapshot. */
public record SettlementDto(
        Long id,
        Long aggregatorId,
        String aggregatorCode,
        String aggregatorName,
        LocalDate periodStart,
        LocalDate periodEnd,
        long orders,
        BigDecimal grossAmount,
        BigDecimal commissionRate,
        BigDecimal commissionAmount,
        BigDecimal netPayable,
        String status,
        String note,
        Instant createdAt,
        Instant settledAt
) {

    public static SettlementDto from(Settlement s) {
        return new SettlementDto(
                s.getId(),
                s.getAggregatorId(),
                s.getAggregatorCode(),
                s.getAggregatorName(),
                s.getPeriodStart(),
                s.getPeriodEnd(),
                s.getOrders(),
                s.getGrossAmount(),
                s.getCommissionRate(),
                s.getCommissionAmount(),
                s.getNetPayable(),
                s.getStatus(),
                s.getNote(),
                s.getCreatedAt(),
                s.getSettledAt()
        );
    }
}
