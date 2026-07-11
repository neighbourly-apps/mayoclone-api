package com.mayoclone.dto;

import java.time.Instant;
import java.util.List;

/**
 * Full detail for one account in the super-admin panel: the {@link AdminAccountDto}
 * plus its payment history and its mailboxes/vendors. NO secrets are ever included
 * (no password hash, no IMAP password, no OAuth refresh token).
 */
public record AdminAccountDetailDto(
        AdminAccountDto account,
        List<Payment> payments,
        List<Mailbox> mailboxes
) {

    /** A captured subscription payment. */
    public record Payment(
            long amount,
            String currency,
            String provider,
            String providerPaymentId,
            Instant createdAt
    ) {
    }

    /** A mailbox/vendor source. Only non-secret connection metadata. */
    public record Mailbox(
            Long id,
            String sourceType,
            String ownerEmail,
            String oauthEmail,
            String imapHost,
            boolean active,
            Instant lastSyncedAt
    ) {
    }
}
