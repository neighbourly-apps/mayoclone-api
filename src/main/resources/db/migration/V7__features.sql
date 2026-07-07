-- V7__features.sql — feature set additions (PostgreSQL). Flyway owns the schema;
-- Hibernate then validate()s these against the entities. H2 test suites use
-- create-drop and never run this file.

-- ---------------------------------------------------------------------------
-- irctc_order: optional free-text reason captured when an order is CANCELLED.
-- ---------------------------------------------------------------------------
ALTER TABLE irctc_order ADD COLUMN cancellation_reason VARCHAR(500);

-- ---------------------------------------------------------------------------
-- menu_item: optional image URL for the catalog item.
-- ---------------------------------------------------------------------------
ALTER TABLE menu_item ADD COLUMN image_url VARCHAR(1024);

-- ---------------------------------------------------------------------------
-- notification_setting: per-account outbound webhook configuration. One row per
-- tenant (unique account_id). Toggles gate which order events fire a webhook.
-- ---------------------------------------------------------------------------
CREATE TABLE notification_setting (
    id            BIGSERIAL PRIMARY KEY,
    account_id    BIGINT NOT NULL,
    webhook_url   VARCHAR(1024),
    on_new_order  BOOLEAN NOT NULL DEFAULT TRUE,
    on_assigned   BOOLEAN NOT NULL DEFAULT FALSE,
    on_cancelled  BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_notification_setting_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT uk_notification_setting_account UNIQUE (account_id)
);
CREATE INDEX idx_notification_setting_account ON notification_setting (account_id);
