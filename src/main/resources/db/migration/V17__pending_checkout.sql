-- V17__pending_checkout.sql — bind a Razorpay order to the plan + amount + account
-- it was CREATED for, at checkout time. This closes a plan-upgrade fraud where the
-- granted plan/amount came from the client /verify body: the Razorpay signature only
-- binds order_id|payment_id, so a tenant could pay for pro-monthly and verify as
-- pro-annual. /verify now derives the activated plan/period/amount from THIS row,
-- ignoring the client-sent plan, and rejects an order it did not create (or that
-- belongs to another account).
-- Flyway owns the schema; Hibernate runs in validate mode against it.
CREATE TABLE pending_checkout (
    order_id    VARCHAR(128) PRIMARY KEY,      -- Razorpay order id (server-issued)
    account_id  BIGINT NOT NULL,               -- the account that checked out
    plan_code   VARCHAR(64) NOT NULL,          -- authoritative plan for this order
    amount      BIGINT NOT NULL,               -- authoritative amount (minor units / paise)
    currency    VARCHAR(8) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_pending_checkout_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_pending_checkout_account ON pending_checkout (account_id);
