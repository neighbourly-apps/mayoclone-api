-- V22__vendor_dashboard_credential.sql — per-vendor dashboard login used by the
-- dashboard order-enrichment framework (PostgreSQL). Flyway owns the schema;
-- Hibernate then validate()s the VendorDashboardCredential entity against it. H2
-- test suites use create-drop and never run this file.
--
-- IRCTC eCatering confirmation emails carry NO passenger/customer name. This table
-- stores the vendor's DASHBOARD login (plain username+password — the portal has no
-- CAPTCHA/OTP) so a background job can look the order up by its external_order_id and
-- back-fill the missing name. username/password/session_token are OPAQUE ciphertext:
-- the app encrypts them with AES-256-GCM (AesGcmStringConverter) before they ever
-- reach this table, exactly like vendor.imap_password. NEVER stored or logged in clear.
-- ---------------------------------------------------------------------------
CREATE TABLE vendor_dashboard_credential (
    id                 BIGSERIAL PRIMARY KEY,
    account_id         BIGINT NOT NULL,
    vendor_id          BIGINT NOT NULL,
    provider           VARCHAR(64) NOT NULL DEFAULT 'IRCTC_ECATERING',
    -- Encrypted at rest (base64(IV||ciphertext||tag)); opaque varchar, never plaintext.
    username           VARCHAR(1024),
    password           VARCHAR(1024),
    session_token      VARCHAR(4096),
    session_expires_at TIMESTAMP WITH TIME ZONE,
    status             VARCHAR(32) NOT NULL DEFAULT 'NEW',
    last_error         TEXT,
    created_at         TIMESTAMP WITH TIME ZONE,
    updated_at         TIMESTAMP WITH TIME ZONE
);

-- One dashboard login per vendor.
CREATE UNIQUE INDEX uk_dash_cred_vendor ON vendor_dashboard_credential (vendor_id);

-- Tenant-scoped listing / lookup.
CREATE INDEX idx_dash_cred_account ON vendor_dashboard_credential (account_id);
