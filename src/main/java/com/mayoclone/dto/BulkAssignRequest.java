package com.mayoclone.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Body for {@code POST /api/orders/bulk/assign}. */
public record BulkAssignRequest(
        @NotEmpty List<Long> ids,
        @NotNull Long riderId
) {
}
