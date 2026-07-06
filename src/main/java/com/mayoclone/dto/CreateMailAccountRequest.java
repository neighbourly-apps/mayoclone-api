package com.mayoclone.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Request body for creating a mail account. */
public record CreateMailAccountRequest(
        @NotBlank String label,
        @NotBlank String imapHost,
        @Min(1) @Max(65535) int imapPort,
        @NotBlank String username,
        @NotBlank String password,
        Boolean useSsl
) {
}
