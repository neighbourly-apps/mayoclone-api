-- V10__ingest_job.sql — durable background job queue (PostgreSQL). Flyway owns
-- the schema; Hibernate then validate()s the IngestJob entity against it. H2 test
-- suites use create-drop and never run this file (SKIP LOCKED is Postgres-only and
-- is exercised in the ONE Docker-gated Testcontainers suite).

-- ---------------------------------------------------------------------------
-- ingest_job: a durable, at-least-once work item. Workers across app instances
-- claim runnable rows with SELECT ... FOR UPDATE SKIP LOCKED so no two workers
-- ever grab the same job. Failures back off exponentially; exhausted jobs
-- dead-letter to status='DEAD'. A dedup_key coalesces a burst of pushes for one
-- mailbox into a single PENDING/RUNNING job.
-- ---------------------------------------------------------------------------
CREATE TABLE ingest_job (
    id           BIGSERIAL PRIMARY KEY,
    job_type     VARCHAR(64) NOT NULL,
    account_id   BIGINT,
    vendor_id    BIGINT,
    payload      TEXT,
    dedup_key    VARCHAR(255),
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts     INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 8,
    run_after    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    locked_by    VARCHAR(128),
    locked_at    TIMESTAMP WITH TIME ZONE,
    last_error   TEXT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Runnable-job lookup used by claim(): PENDING rows whose run_after has arrived,
-- oldest first.
CREATE INDEX idx_ingest_job_status_run_after ON ingest_job (status, run_after);

-- Cheap per-tenant listing / debugging.
CREATE INDEX idx_ingest_job_vendor ON ingest_job (vendor_id);

-- Coalescing: at most ONE active (PENDING or RUNNING) job may exist per dedup_key.
-- A DONE/FAILED/DEAD job with the same key does NOT block a fresh enqueue, so this
-- is a partial unique index rather than a plain UNIQUE constraint.
CREATE UNIQUE INDEX uq_ingest_job_dedup_active
    ON ingest_job (dedup_key)
    WHERE dedup_key IS NOT NULL AND status IN ('PENDING', 'RUNNING');

-- ---------------------------------------------------------------------------
-- vendor: Gmail push-ingestion bookkeeping (used by the Gmail-push phase that
-- runs ON this queue). Nullable so existing rows and non-Gmail vendors are fine.
--   gmail_history_id       — last Gmail historyId processed for this mailbox.
--   gmail_watch_expiration — when the current users.watch registration lapses.
-- ---------------------------------------------------------------------------
ALTER TABLE vendor ADD COLUMN gmail_history_id VARCHAR(64);
ALTER TABLE vendor ADD COLUMN gmail_watch_expiration TIMESTAMP WITH TIME ZONE;
