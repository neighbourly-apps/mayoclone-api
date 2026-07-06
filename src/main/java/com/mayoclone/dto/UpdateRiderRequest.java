package com.mayoclone.dto;

import jakarta.validation.constraints.Size;

/** Body for {@code PATCH /api/riders/{id}}; all fields optional (null = leave unchanged). */
public record UpdateRiderRequest(
        @Size(max = 255) String name,
        @Size(max = 255) String phone,
        Boolean active
) {
}
