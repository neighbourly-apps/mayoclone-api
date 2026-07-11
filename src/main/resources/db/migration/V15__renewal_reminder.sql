-- V15__renewal_reminder.sql — "your subscription renews soon" reminder emails.
-- Flyway owns the schema; Hibernate runs in validate mode against it.

-- ---------------------------------------------------------------------------
-- Per-period dedupe marker for the renewal-reminder sweep. NULL means "not yet
-- reminded for the current period". Reset to NULL on every (re)activation so a
-- fresh period reminds again; the sweep only selects rows where this is NULL.
-- ---------------------------------------------------------------------------
ALTER TABLE account
    ADD COLUMN renewal_reminder_sent_at TIMESTAMP WITH TIME ZONE;
