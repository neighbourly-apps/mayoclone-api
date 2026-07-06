-- V5__order_lifecycle.sql — order lifecycle, type, payment, riders & status timeline (PostgreSQL).
-- Flyway owns the schema; Hibernate then validate()s these against the entities.

-- ---------------------------------------------------------------------------
-- rider: a delivery boy owned by a tenant.
-- ---------------------------------------------------------------------------
CREATE TABLE rider (
    id         BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    name       VARCHAR(255),
    phone      VARCHAR(255),
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_rider_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_rider_account ON rider (account_id);

-- ---------------------------------------------------------------------------
-- order_status_event: append-only status timeline for an order.
-- ("event_at" — "at" is a dialect-sensitive identifier.)
-- ---------------------------------------------------------------------------
CREATE TABLE order_status_event (
    id       BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    status   VARCHAR(32) NOT NULL,
    event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    note     VARCHAR(500),
    CONSTRAINT fk_status_event_order FOREIGN KEY (order_id) REFERENCES irctc_order (id)
);
CREATE INDEX idx_status_event_order ON order_status_event (order_id);

-- ---------------------------------------------------------------------------
-- irctc_order: lifecycle + type + payment + rider assignment.
-- ---------------------------------------------------------------------------
ALTER TABLE irctc_order ADD COLUMN order_type   VARCHAR(16);
ALTER TABLE irctc_order ADD COLUMN payment_mode VARCHAR(16);
ALTER TABLE irctc_order ADD COLUMN rider_id     BIGINT;
ALTER TABLE irctc_order ADD COLUMN assigned_at  TIMESTAMP WITH TIME ZONE;
ALTER TABLE irctc_order ADD COLUMN delivered_at TIMESTAMP WITH TIME ZONE;

-- Backfill existing rows: ONLINE / PREPAID; lifecycle re-based to NEW.
UPDATE irctc_order SET order_type = 'ONLINE'  WHERE order_type IS NULL;
UPDATE irctc_order SET payment_mode = 'PREPAID' WHERE payment_mode IS NULL;
UPDATE irctc_order SET status = 'NEW'
    WHERE status IS NULL
       OR status NOT IN ('NEW','ACCEPTED','PREPARING','READY',
                         'OUT_FOR_DELIVERY','DELIVERED','UNDELIVERED','CANCELLED');

-- Enum-as-varchar columns: sane defaults + NOT NULL now that they are backfilled.
ALTER TABLE irctc_order ALTER COLUMN order_type   SET DEFAULT 'ONLINE';
ALTER TABLE irctc_order ALTER COLUMN order_type   SET NOT NULL;
ALTER TABLE irctc_order ALTER COLUMN payment_mode SET DEFAULT 'PREPAID';
ALTER TABLE irctc_order ALTER COLUMN payment_mode SET NOT NULL;
ALTER TABLE irctc_order ALTER COLUMN status       SET DEFAULT 'NEW';
ALTER TABLE irctc_order ALTER COLUMN status       SET NOT NULL;

-- DIRECT orders have no aggregator. Multiple NULL aggregator_id rows are allowed
-- by the existing UNIQUE (aggregator_id, external_order_id) (Postgres NULLs distinct).
ALTER TABLE irctc_order ALTER COLUMN aggregator_id DROP NOT NULL;

ALTER TABLE irctc_order
    ADD CONSTRAINT fk_order_rider FOREIGN KEY (rider_id) REFERENCES rider (id);

CREATE INDEX idx_order_account_status ON irctc_order (account_id, status);
CREATE INDEX idx_order_account_rider  ON irctc_order (account_id, rider_id);
