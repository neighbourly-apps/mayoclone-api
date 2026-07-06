package com.mayoclone.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Body for {@code POST /api/menu-items}. */
public record CreateMenuItemRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String category,
        @NotNull @PositiveOrZero BigDecimal price,
        Boolean available
) {
}
