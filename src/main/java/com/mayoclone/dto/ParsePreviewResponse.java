package com.mayoclone.dto;

import com.mayoclone.parser.ParsedOrder;

import java.util.List;

/**
 * Response for {@code POST /api/dev/parse-preview} — a non-persisting dry run of the
 * ingestion pipeline used to TUNE parsers against real emails.
 *
 * @param matchedAggregator the aggregator the sender routed to, or {@code null}
 * @param parsed            the full {@link ParsedOrder} (null fields shown as null),
 *                          or {@code null} if no aggregator matched
 * @param wouldPersist      true iff an aggregator matched AND a parser produced an order
 * @param warnings          human-readable notes about missing/defaulted fields
 */
public record ParsePreviewResponse(
        AggregatorRef matchedAggregator,
        ParsedOrder parsed,
        boolean wouldPersist,
        List<String> warnings
) {
    /** Minimal aggregator identity for the preview. */
    public record AggregatorRef(String code, String name) {
    }
}
