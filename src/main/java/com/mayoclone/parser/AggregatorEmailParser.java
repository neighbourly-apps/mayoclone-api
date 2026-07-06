package com.mayoclone.parser;

/**
 * Strategy for turning one aggregator's order-confirmation email into a
 * {@link ParsedOrder}. Implementations are auto-registered as Spring beans;
 * ingestion picks the first whose {@link #supports} returns true.
 */
public interface AggregatorEmailParser {

    /** @return true if this parser recognises the email (by sender or subject). */
    boolean supports(String from, String subject);

    /** Parse the email into a {@link ParsedOrder}, degrading gracefully on missing fields. */
    ParsedOrder parse(String from, String subject, String body, String messageId);
}
