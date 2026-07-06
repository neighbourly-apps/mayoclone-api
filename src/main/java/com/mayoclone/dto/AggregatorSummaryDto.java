package com.mayoclone.dto;

import com.mayoclone.domain.Aggregator;

/** Compact aggregator view nested inside an {@link OrderDto}. */
public record AggregatorSummaryDto(Long id, String code, String name, String brandColor) {

    public static AggregatorSummaryDto from(Aggregator a) {
        if (a == null) {
            return null;
        }
        return new AggregatorSummaryDto(a.getId(), a.getCode(), a.getName(), a.getBrandColor());
    }
}
