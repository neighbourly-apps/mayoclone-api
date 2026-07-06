package com.mayoclone.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Vendor signup request. {@code imapUsername} defaults to {@code ownerEmail}
 * when blank; {@code imapPort} defaults to 993 and {@code useSsl} to true.
 */
public record CreateVendorRequest(
        @NotBlank String restaurantName,
        @NotBlank @Email String ownerEmail,
        @NotBlank String stationCode,
        String stationName,
        @NotBlank String phone,
        String gstin,
        String addressLine,
        @NotBlank String imapHost,
        @Min(1) @Max(65535) Integer imapPort,
        String imapUsername,
        @NotBlank String imapPassword,
        Boolean useSsl
) {
}
