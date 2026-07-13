package com.mayoclone.dto;

/**
 * The original source of a stored order, for an operator to read/re-parse the raw
 * email behind a low-confidence parse. Kept OUT of the orders list DTO ({@link OrderDto})
 * because {@code rawEmail} is large; served only by GET /api/orders/{id}/raw.
 *
 * <p>{@code from} is the sender address when known; orders do not persist the raw
 * sender (only the dead-letter does), so it is currently null for ingested orders.
 */
public record OrderRawDto(
        Long id,
        String subject,
        String from,
        String rawEmail
) {
}
