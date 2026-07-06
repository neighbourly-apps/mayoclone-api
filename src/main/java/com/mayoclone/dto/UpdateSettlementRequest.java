package com.mayoclone.dto;

import jakarta.validation.constraints.Size;

/** Body for {@code PATCH /api/settlements/{id}}. Both fields optional. */
public record UpdateSettlementRequest(
        String status,
        @Size(max = 1000) String note
) {
}
