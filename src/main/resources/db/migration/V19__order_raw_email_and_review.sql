-- V19__order_raw_email_and_review.sql — make the ingestion pipeline lossless.
--
-- (1) Retain the FULL raw email on every order (ultimate safety net: even if a
--     field parsed wrong, the source is always recoverable & re-parseable).
-- (2) Flag low-confidence parses (needs_review) with a human reason instead of
--     silently trusting a bad parse — the order is ALWAYS still saved.
-- (3) Make the dead-letter fully recoverable/replayable: keep the untruncated
--     body and record when a failure was resolved (audit trail for replays).
--
-- All adds are nullable or NOT NULL-with-default so they apply cleanly on a
-- populated table. Flyway owns the schema; Hibernate runs in validate mode.

-- (1) + (2): orders carry the raw source + review verdict.
ALTER TABLE irctc_order ADD COLUMN raw_email     TEXT;
ALTER TABLE irctc_order ADD COLUMN needs_review  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE irctc_order ADD COLUMN review_reason VARCHAR(500);

-- Filter the review queue efficiently (list orders WHERE needs_review = true).
CREATE INDEX idx_order_account_review ON irctc_order (account_id, needs_review);

-- (3): dead-letter keeps the full body (replay source) + a resolution timestamp.
ALTER TABLE ingest_failure ADD COLUMN raw_body    TEXT;
ALTER TABLE ingest_failure ADD COLUMN resolved_at TIMESTAMP WITH TIME ZONE;
