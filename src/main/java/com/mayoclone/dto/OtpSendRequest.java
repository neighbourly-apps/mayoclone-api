package com.mayoclone.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body for POST /api/auth/otp/send. */
public record OtpSendRequest(
        @NotBlank @Email String email
) {
}
