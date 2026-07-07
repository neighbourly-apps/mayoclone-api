package com.mayoclone.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Registration payload. Password strength: min 10 chars. */
public record RegisterRequest(
        @NotBlank String businessName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 10, max = 200, message = "password must be at least 10 characters") String password,
        String stationCode,
        String stationName,
        String gstin,
        String addressLine,
        String phone,
        // Optional JWT from POST /api/auth/otp/verify. REQUIRED only when
        // mayoclone.auth.require-email-otp=true; ignored/optional otherwise.
        String emailVerificationToken
) {
}
