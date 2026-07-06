package com.mayoclone.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /api/riders}. */
public record CreateRiderRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String phone
) {
}
