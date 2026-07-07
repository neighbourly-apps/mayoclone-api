package com.mayoclone.dto;

import com.mayoclone.domain.OrderStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Body for {@code POST /api/orders/bulk/status}. */
public record BulkStatusRequest(
        @NotEmpty List<Long> ids,
        @NotNull OrderStatus status,
        @Size(max = 500) String note
) {
}
