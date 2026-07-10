-- V12__vendor_sync_health.sql — per-vendor mailbox sync-health + scheduling columns
-- (PostgreSQL). Flyway owns the schema; Hibernate then validate()s the Vendor entity
-- against it. H2 test suites use create-drop and never run this file.
--
-- These columns move mailbox polling OFF the inline per-instance sweep and ONTO the
-- durable job queue: the MailboxPollDispatcher selects "due" vendors here and enqueues
-- one MAILBOX_SYNC job per vendor; the MailboxSyncJobHandler records the result and
-- reschedules next_poll_at (per-vendor exponential backoff on failure).
-- ---------------------------------------------------------------------------

--   next_poll_at        — earliest time this mailbox is due for another sync. NULL
--                         means "never polled → poll immediately". The dispatcher
--                         bumps it forward on enqueue; the handler resets it on
--                         completion (now + interval on success, backoff on failure).
--   poll_failure_count  — consecutive mailbox failures, drives the exponential backoff.
--   last_sync_error     — truncated last mailbox error (shown on the vendor card).
ALTER TABLE vendor ADD COLUMN next_poll_at       TIMESTAMP WITH TIME ZONE;
ALTER TABLE vendor ADD COLUMN poll_failure_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE vendor ADD COLUMN last_sync_error    TEXT;

-- "Due vendors" lookup used by MailboxPollDispatcher at 2000+ scale:
--   WHERE active AND source_type <> 'FORWARDING' AND (next_poll_at IS NULL OR <= now())
--   ORDER BY next_poll_at NULLS FIRST LIMIT :batch
-- The composite (active, source_type, next_poll_at) lets Postgres satisfy the filter
-- and the ordered LIMIT from the index instead of scanning every vendor each tick.
CREATE INDEX idx_vendor_due_poll ON vendor (active, source_type, next_poll_at);
