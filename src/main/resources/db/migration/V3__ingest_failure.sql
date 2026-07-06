-- V3__ingest_failure.sql — review queue / dead-letter for un-ingestable mail.

CREATE TABLE ingest_failure (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT,                    -- nullable: unmatched inbound recipient
    vendor_id    BIGINT,
    from_address VARCHAR(255),
    subject      VARCHAR(1000),
    reason       VARCHAR(32) NOT NULL,      -- NO_AGGREGATOR_MATCH | NO_PARSER | PARSE_FAILED | UNMATCHED_RECIPIENT
    raw_snippet  VARCHAR(2000),             -- truncated body; NEVER secrets
    source_type  VARCHAR(32),
    message_id   VARCHAR(512),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_ingest_failure_account ON ingest_failure (account_id, created_at DESC);
