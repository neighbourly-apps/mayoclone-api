package com.mayoclone.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Body for {@code PATCH /api/menu-items/{id}}. All fields optional (partial update). */
public record UpdateMenuItemRequest(
        @Size(max = 255) String name,
        @Size(max = 255) String category,
        @Size(max = 1024) String imageUrl,
        @PositiveOrZero BigDecimal price,
        Boolean available
) {
}
