-- V16__account_indexes.sql — indexes to keep the account table off sequential scans.
-- Flyway owns the schema; Hibernate runs in validate mode against it. Applies cleanly
-- on a populated DB (plain B-tree / partial B-tree creations, no table rewrite).

-- ---------------------------------------------------------------------------
-- Renewal-reminder sweep (RenewalReminderJob, hourly). The query is:
--   role <> SUPER_ADMIN AND renewal_reminder_sent_at IS NULL AND (
--     (subscription_status = 'ACTIVE'   AND current_period_end in (now, cutoff]) OR
--     (subscription_status = 'TRIALING' AND trial_ends_at      in (now, cutoff]))
-- Partial indexes restricted to the un-reminded rows (renewal_reminder_sent_at IS
-- NULL) — the exact predicate the sweep filters on — so the index stays tiny and the
-- planner can range-scan the period/status columns instead of scanning the table.
-- ---------------------------------------------------------------------------
CREATE INDEX idx_account_reminder_active
    ON account (subscription_status, current_period_end)
    WHERE renewal_reminder_sent_at IS NULL;

CREATE INDEX idx_account_reminder_trial
    ON account (subscription_status, trial_ends_at)
    WHERE renewal_reminder_sent_at IS NULL;

-- ---------------------------------------------------------------------------
-- Admin console list/search (AccountRepository.adminSearch): filters by
-- subscription_status and orders by created_at DESC over ALL tenants. Index
-- created_at (for the ORDER BY / pagination) and subscription_status (for the
-- status filter and the admin stats counts by status).
-- ---------------------------------------------------------------------------
CREATE INDEX idx_account_created_at ON account (created_at);
CREATE INDEX idx_account_subscription_status ON account (subscription_status);
