-- V2__mail_source.sql — pluggable mail-source support on vendor (PostgreSQL).
-- Generalises the vendor from IMAP-only to {IMAP, FORWARDING, GMAIL_OAUTH}.

ALTER TABLE vendor
    ADD COLUMN source_type         VARCHAR(32)  NOT NULL DEFAULT 'IMAP',
    ADD COLUMN ingest_address      VARCHAR(255),
    ADD COLUMN oauth_email         VARCHAR(255),
    -- AES-256-GCM encrypted (base64 blob) — wider column, same as imap_password.
    ADD COLUMN oauth_refresh_token VARCHAR(2048),
    ADD COLUMN oauth_connected_at  TIMESTAMP WITH TIME ZONE;

-- Per-vendor forwarding address must be globally unique (NULLs allowed for
-- non-forwarding vendors — Postgres permits multiple NULLs under UNIQUE).
ALTER TABLE vendor
    ADD CONSTRAINT uk_vendor_ingest_address UNIQUE (ingest_address);
