-- V21__order_dedup_unique.sql — DB-level idempotency for email ingestion.
--
-- Orders dedup in the app on source_message_id (the email Message-ID, or a stable
-- fallback hash when the header is absent). This adds a DB guarantee so a duplicate
-- is rejected even under a race (two concurrent polls of the same mailbox, a retry
-- overlapping the original, the inbound webhook racing an IMAP sync).
--
-- PARTIAL unique index: uniqueness is enforced per tenant (account_id) only for rows
-- that actually carry a source_message_id. Rows with a NULL source_message_id (an
-- order created by some path without an email id) are exempt, so this never blocks a
-- legitimate insert. There are no duplicates today, so it applies cleanly on the
-- populated table. Flyway owns the schema; Hibernate runs ddl-auto=validate — an
-- index requires no entity change, so validation stays happy.

CREATE UNIQUE INDEX uq_order_account_source_message
    ON irctc_order (account_id, source_message_id)
    WHERE source_message_id IS NOT NULL;
