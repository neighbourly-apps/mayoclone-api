package com.mayoclone.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Request body for creating/updating a managed aggregator. */
public record CreateAggregatorRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotEmpty List<String> senderDomains,
        String subjectHint,
        @NotBlank String brandColor,
        String websiteUrl,
        Boolean active
) {
}
