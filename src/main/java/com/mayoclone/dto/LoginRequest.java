package com.mayoclone.dto;

import jakarta.validation.constraints.NotBlank;

/** Login payload. Errors are intentionally generic to avoid user-enumeration. */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
}
