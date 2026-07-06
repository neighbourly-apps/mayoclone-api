-- V1__init.sql — MayoClone initial schema (PostgreSQL).
-- Flyway owns the schema; Hibernate runs in validate mode against it.

-- ---------------------------------------------------------------------------
-- account: the tenant boundary AND the login identity (business).
-- ---------------------------------------------------------------------------
CREATE TABLE account (
    id            BIGSERIAL PRIMARY KEY,
    business_name VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    station_code  VARCHAR(255),
    station_name  VARCHAR(255),
    gstin         VARCHAR(255),
    address_line  VARCHAR(255),
    phone         VARCHAR(255),
    role          VARCHAR(255) NOT NULL,
    status        VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_account_email UNIQUE (email)
);

-- ---------------------------------------------------------------------------
-- refresh_token: hashed, rotating refresh tokens with reuse detection.
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_token (
    id         BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    family_id  VARCHAR(64) NOT NULL,
    revoked    BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_refresh_token_account ON refresh_token (account_id);
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);

-- ---------------------------------------------------------------------------
-- aggregator: GLOBAL catalog shared across tenants (seeded on startup).
-- ---------------------------------------------------------------------------
CREATE TABLE aggregator (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(255) NOT NULL,
    name         VARCHAR(255),
    subject_hint VARCHAR(255),
    brand_color  VARCHAR(255),
    website_url  VARCHAR(255),
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_aggregator_code UNIQUE (code)
);

CREATE TABLE aggregator_sender_domain (
    aggregator_id BIGINT NOT NULL,
    domain        VARCHAR(255),
    CONSTRAINT fk_sender_domain_aggregator FOREIGN KEY (aggregator_id) REFERENCES aggregator (id)
);
CREATE INDEX idx_sender_domain_aggregator ON aggregator_sender_domain (aggregator_id);

-- ---------------------------------------------------------------------------
-- vendor: a mailbox/source owned by an account. imap_password is stored
-- AES-256-GCM encrypted (base64 blob), hence the wider column.
-- ---------------------------------------------------------------------------
CREATE TABLE vendor (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT NOT NULL,
    restaurant_name VARCHAR(255),
    owner_email     VARCHAR(255),
    station_code    VARCHAR(255),
    station_name    VARCHAR(255),
    phone           VARCHAR(255),
    gstin           VARCHAR(255),
    address_line    VARCHAR(255),
    imap_host       VARCHAR(255),
    imap_port       INTEGER NOT NULL,
    imap_username   VARCHAR(255),
    imap_password   VARCHAR(1024),
    use_ssl         BOOLEAN NOT NULL,
    active          BOOLEAN NOT NULL,
    last_synced_at  TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_vendor_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_vendor_account ON vendor (account_id);

-- ---------------------------------------------------------------------------
-- irctc_order: a parsed order, always owned by a tenant.
-- ---------------------------------------------------------------------------
CREATE TABLE irctc_order (
    id                     BIGSERIAL PRIMARY KEY,
    aggregator_id          BIGINT,
    account_id             BIGINT NOT NULL,
    vendor_id              BIGINT,
    external_order_id      VARCHAR(255),
    pnr                    VARCHAR(255),
    train_number           VARCHAR(255),
    train_name             VARCHAR(255),
    coach                  VARCHAR(255),
    berth                  VARCHAR(255),
    boarding_station_code  VARCHAR(255),
    delivery_station_code  VARCHAR(255),
    delivery_station_name  VARCHAR(255),
    passenger_name         VARCHAR(255),
    passenger_phone        VARCHAR(255),
    delivery_date          DATE,
    delivery_slot          VARCHAR(255),
    amount                 NUMERIC(19, 2),
    currency               VARCHAR(255),
    status                 VARCHAR(255),
    subject                VARCHAR(1000),
    source_message_id      VARCHAR(512),
    placed_at              TIMESTAMP WITH TIME ZONE,
    created_at             TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_order_aggregator_external UNIQUE (aggregator_id, external_order_id),
    CONSTRAINT fk_order_aggregator FOREIGN KEY (aggregator_id) REFERENCES aggregator (id),
    CONSTRAINT fk_order_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_order_account_placed ON irctc_order (account_id, placed_at DESC);
CREATE INDEX idx_order_account_station ON irctc_order (account_id, delivery_station_code);
CREATE INDEX idx_order_account_date ON irctc_order (account_id, delivery_date);

CREATE TABLE order_item (
    order_id BIGINT NOT NULL,
    name     VARCHAR(255),
    qty      INTEGER NOT NULL,
    price    NUMERIC(19, 2),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES irctc_order (id)
);
CREATE INDEX idx_order_item_order ON order_item (order_id);
