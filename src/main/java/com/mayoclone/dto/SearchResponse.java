package com.mayoclone.dto;

import java.util.List;

/** Response body for {@code GET /api/search}. */
public record SearchResponse(String query, int count, List<OrderDto> orders) {
}
