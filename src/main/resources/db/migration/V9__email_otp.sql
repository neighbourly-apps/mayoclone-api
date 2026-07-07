-- V9__email_otp.sql — email OTP verification (PostgreSQL). Flyway owns the schema;
-- Hibernate then validate()s these against the entities. H2 test suites use
-- create-drop and never run this file.

-- ---------------------------------------------------------------------------
-- account: whether the owner has proven control of their email via an OTP.
-- Default FALSE so existing rows and the tokenless (flag-off) register path work.
-- ---------------------------------------------------------------------------
ALTER TABLE account ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- email_otp: one-time 6-digit codes, stored HASHED (SHA-256). At most one
-- unconsumed row per email is active; sending a new code consumes prior ones.
-- ---------------------------------------------------------------------------
CREATE TABLE email_otp (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    code_hash   VARCHAR(64) NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0,
    consumed    BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_email_otp_email ON email_otp (email, consumed, created_at);
