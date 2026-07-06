package com.mayoclone.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Computed (not persisted) per-aggregator reconciliation over a delivery-date
 * window. Covers ONLINE orders (aggregator not null) that are NOT CANCELLED.
 */
public record SettlementSummaryDto(
        LocalDate from,
        LocalDate to,
        List<Row> rows,
        Totals totals
) {

    public record Row(
            Long aggregatorId,
            String aggregatorCode,
            String name,
            long orders,
            BigDecimal grossAmount,
            BigDecimal commissionRate,
            BigDecimal commissionAmount,
            BigDecimal netPayable
    ) {
    }

    public record Totals(
            long orders,
            BigDecimal grossAmount,
            BigDecimal commissionAmount,
            BigDecimal netPayable
    ) {
    }
}
