package com.mayoclone.dto;

import com.mayoclone.domain.Aggregator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Full view of a managed aggregator row. */
public record AggregatorDto(
        Long id,
        String code,
        String name,
        List<String> senderDomains,
        String subjectHint,
        String brandColor,
        String websiteUrl,
        boolean active,
        BigDecimal commissionRate,
        Instant createdAt
) {

    public static AggregatorDto from(Aggregator a) {
        return new AggregatorDto(
                a.getId(),
                a.getCode(),
                a.getName(),
                List.copyOf(a.getSenderDomains()),
                a.getSubjectHint(),
                a.getBrandColor(),
                a.getWebsiteUrl(),
                a.isActive(),
                a.getCommissionRate(),
                a.getCreatedAt()
        );
    }
}
