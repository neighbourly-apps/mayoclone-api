package com.mayoclone.domain;

/**
 * How a {@link Vendor}'s aggregator mail reaches us.
 *
 * <ul>
 *   <li>{@link #IMAP} — we pull UNSEEN messages from the vendor's mailbox (legacy).</li>
 *   <li>{@link #FORWARDING} — push-only: the vendor forwards aggregator mail to a
 *       per-vendor {@code ingestAddress}; it arrives via the inbound webhook.</li>
 *   <li>{@link #GMAIL_OAUTH} — we hold an OAuth refresh token and pull via the
 *       Gmail REST API (readonly scope).</li>
 * </ul>
 */
public enum MailSourceType {
    IMAP,
    FORWARDING,
    GMAIL_OAUTH
}
