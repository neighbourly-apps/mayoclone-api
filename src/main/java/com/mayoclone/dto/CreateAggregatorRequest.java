package com.mayoclone.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

/** Request body for creating/updating a managed aggregator. */
public record CreateAggregatorRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotEmpty List<String> senderDomains,
        String subjectHint,
        @NotBlank String brandColor,
        String websiteUrl,
        Boolean active,
        /** Optional; fraction 0..1 (0.15 = 15%). Defaults to 0.15 when omitted. */
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal commissionRate
) {
}
