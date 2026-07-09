-- V11__gmail_push.sql — Gmail Pub/Sub push idempotency ledger (PostgreSQL).
-- Flyway owns the schema; Hibernate then validate()s the ProcessedPush entity
-- against it. H2 test suites use create-drop and never run this file.
--
-- The vendor.gmail_history_id + vendor.gmail_watch_expiration columns already
-- landed in V10; this migration only adds the push-dedup table.

-- ---------------------------------------------------------------------------
-- processed_push: one row per Pub/Sub delivery we have ALREADY handled, keyed by
-- the Pub/Sub message.messageId. Pub/Sub delivery is at-least-once, so the push
-- endpoint checks this table to skip re-doing work for a re-delivered message.
-- Correctness never depends on it (the job queue coalesces on dedup_key and the
-- ingestion pipeline dedups on the email Message-ID); it is purely an
-- optimisation to avoid redundant enqueues. Rows are purged after a retention
-- window by GmailWatchRenewer.
-- ---------------------------------------------------------------------------
CREATE TABLE processed_push (
    message_id  VARCHAR(255) PRIMARY KEY,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Supports the retention purge (delete rows older than the cutoff).
CREATE INDEX idx_processed_push_received_at ON processed_push (received_at);
