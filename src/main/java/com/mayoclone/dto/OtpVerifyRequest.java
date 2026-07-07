package com.mayoclone.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body for POST /api/auth/otp/verify. */
public record OtpVerifyRequest(
        @NotBlank @Email String email,
        @NotBlank String code
) {
}
