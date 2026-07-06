package com.mayoclone.domain;

/** Why a raw message could not be turned into an order (dead-letter reason). */
public enum IngestFailureReason {
    /** No active aggregator matched the sender domain. */
    NO_AGGREGATOR_MATCH,
    /** An aggregator matched but no parser supported it. */
    NO_PARSER,
    /** A parser matched but threw while extracting fields. */
    PARSE_FAILED,
    /** Inbound webhook recipient did not map to any vendor's ingest address. */
    UNMATCHED_RECIPIENT
}
