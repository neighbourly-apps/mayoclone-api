-- V14__subscription.sql — 14-day free trial + paid subscription (Razorpay).
-- Flyway owns the schema; Hibernate runs in validate mode against it.

-- ---------------------------------------------------------------------------
-- Billing columns on the tenant (account).
--   subscription_status: TRIALING | ACTIVE | EXPIRED | CANCELLED
--   trial_ends_at      : when the 14-day free trial lapses (set on register)
--   current_period_end : end of the current PAID period (extended on payment)
--   plan               : plan code the account is on (e.g. pro-monthly)
-- ---------------------------------------------------------------------------
ALTER TABLE account
    ADD COLUMN subscription_status VARCHAR(16) NOT NULL DEFAULT 'TRIALING',
    ADD COLUMN trial_ends_at       TIMESTAMP WITH TIME ZONE,
    ADD COLUMN current_period_end  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN plan                 VARCHAR(64);

-- Grandfather EVERY pre-existing account (owner/demo/etc.): flip to ACTIVE with a
-- 10-year runway so accounts that predate billing are NEVER locked out by the gate.
UPDATE account
   SET subscription_status = 'ACTIVE',
       current_period_end  = now() + interval '3650 days';

-- ---------------------------------------------------------------------------
-- subscription_payment: an immutable record of each captured payment. The
-- (provider, provider_payment_id) unique constraint makes verify/webhook
-- idempotent (a replayed payment id extends the period at most once).
-- ---------------------------------------------------------------------------
CREATE TABLE subscription_payment (
    id                  BIGSERIAL PRIMARY KEY,
    account_id          BIGINT NOT NULL,
    provider            VARCHAR(32) NOT NULL,
    provider_payment_id VARCHAR(128) NOT NULL,
    amount              BIGINT NOT NULL,
    currency            VARCHAR(8) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_subscription_payment_provider_pid UNIQUE (provider, provider_payment_id),
    CONSTRAINT fk_subscription_payment_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_subscription_payment_account ON subscription_payment (account_id);
