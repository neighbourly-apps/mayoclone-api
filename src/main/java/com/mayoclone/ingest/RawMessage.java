package com.mayoclone.ingest;

/**
 * A source-agnostic raw email, as produced by any {@link MailSource} (or the
 * inbound webhook). This is the single unit the ingestion pipeline consumes.
 */
public record RawMessage(String from, String subject, String body, String messageId) {
}
