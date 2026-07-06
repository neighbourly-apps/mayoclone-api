package com.mayoclone.dto;

import com.mayoclone.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body for {@code PATCH /api/orders/{id}/status}. */
public record UpdateStatusRequest(
        @NotNull OrderStatus status,
        @Size(max = 500) String note
) {
}
