-- Read-agnostic IMAP fetching: track a per-mailbox UID watermark instead of
-- relying on the \Seen (read/unread) flag. Another app or a human reading the
-- inbox must NOT cause us to miss order emails, so we fetch by UID position.
ALTER TABLE vendor ADD COLUMN imap_last_uid    BIGINT;
ALTER TABLE vendor ADD COLUMN imap_uid_validity BIGINT;
