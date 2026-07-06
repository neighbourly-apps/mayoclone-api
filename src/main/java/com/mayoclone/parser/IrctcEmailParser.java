package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;

/**
 * Strategy for turning one aggregator's IRCTC e-catering order email into a
 * {@link ParsedOrder}. Implementations are auto-registered as Spring beans;
 * ingestion first routes the email to an {@link Aggregator} (by sender domain),
 * then picks the first parser whose {@link #supports} returns true for that
 * aggregator.
 */
public interface IrctcEmailParser {

    /** @return true if this parser can handle mail for the given aggregator. */
    boolean supports(Aggregator agg, String from, String subject);

    /** Parse the email into a {@link ParsedOrder}, degrading gracefully on missing fields. */
    ParsedOrder parse(Aggregator agg, String from, String subject, String body, String messageId);
}
