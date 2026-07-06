package com.mayoclone.ingest;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;

import java.util.List;

/**
 * A pluggable way to PULL new raw messages for a vendor. Implementations exist
 * for the pull-based source types ({@link MailSourceType#IMAP},
 * {@link MailSourceType#GMAIL_OAUTH}). {@link MailSourceType#FORWARDING} is
 * push-only (delivered by the inbound webhook) and therefore has NO MailSource.
 */
public interface MailSource {

    /** The source type this implementation handles (used to select it per vendor). */
    MailSourceType type();

    /** Fetch new raw messages for the vendor. Never returns null. */
    List<RawMessage> fetch(Vendor vendor);
}
