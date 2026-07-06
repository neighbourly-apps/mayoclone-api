package com.mayoclone.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Body for {@code POST /api/settlements} — computes and persists a snapshot. */
public record CreateSettlementRequest(
        @NotBlank String aggregatorCode,
        @NotNull LocalDate from,
        @NotNull LocalDate to
) {
}
