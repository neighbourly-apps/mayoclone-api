-- V4__audit_log.sql — append-only, immutable audit trail (PostgreSQL).
-- Immutability is enforced at the DB: a trigger raises on any UPDATE or DELETE,
-- so audit rows can only ever be inserted (same approach as PR #52's hardening).

CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT,                    -- nullable: pre-auth events (login failure, register)
    actor_email  VARCHAR(255),
    action       VARCHAR(64) NOT NULL,
    target_type  VARCHAR(64),
    target_id    VARCHAR(128),
    ip           VARCHAR(64),
    user_agent   VARCHAR(512),
    details_json VARCHAR(2000),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_audit_log_account ON audit_log (account_id, created_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log (action, created_at DESC);

-- Immutability guard: refuse UPDATE/DELETE on audit_log.
CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();

CREATE TRIGGER trg_audit_log_no_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
