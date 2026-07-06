-- V6__menu_settlement.sql — menu management, settlement/reconciliation, aggregator
-- commission rate (PostgreSQL). Flyway owns the schema; Hibernate then validate()s
-- these against the entities.

-- ---------------------------------------------------------------------------
-- menu_item: a tenant-owned catalog item.
-- ---------------------------------------------------------------------------
CREATE TABLE menu_item (
    id         BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    name       VARCHAR(255) NOT NULL,
    category   VARCHAR(255),
    price      NUMERIC(12, 2) NOT NULL,
    available  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_menu_item_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_menu_item_account ON menu_item (account_id);

-- ---------------------------------------------------------------------------
-- settlement: a persisted per-aggregator reconciliation snapshot for a window.
-- ---------------------------------------------------------------------------
CREATE TABLE settlement (
    id                BIGSERIAL PRIMARY KEY,
    account_id        BIGINT NOT NULL,
    aggregator_id     BIGINT,
    aggregator_code   VARCHAR(255),
    aggregator_name   VARCHAR(255),
    period_start      DATE NOT NULL,
    period_end        DATE NOT NULL,
    orders            BIGINT NOT NULL DEFAULT 0,
    gross_amount      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    commission_rate   NUMERIC(5, 4) NOT NULL DEFAULT 0.15,
    commission_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    net_payable       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    note              VARCHAR(1000),
    created_at        TIMESTAMP WITH TIME ZONE,
    settled_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_settlement_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_settlement_aggregator FOREIGN KEY (aggregator_id) REFERENCES aggregator (id)
);
CREATE INDEX idx_settlement_account ON settlement (account_id);
CREATE INDEX idx_settlement_account_created ON settlement (account_id, created_at);

-- ---------------------------------------------------------------------------
-- aggregator: commission rate (fraction, 0.15 = 15%) driving settlement math.
-- ---------------------------------------------------------------------------
ALTER TABLE aggregator ADD COLUMN commission_rate NUMERIC(5, 4) NOT NULL DEFAULT 0.15;
UPDATE aggregator SET commission_rate = 0.15 WHERE commission_rate IS NULL;
