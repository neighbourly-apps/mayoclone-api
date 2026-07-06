package com.mayoclone.dto;

import com.mayoclone.domain.MailAccount;

import java.time.Instant;

/** Safe view of a mail account. Deliberately omits the password. */
public record MailAccountDto(
        Long id,
        String label,
        String imapHost,
        int imapPort,
        String username,
        boolean useSsl,
        boolean active,
        Instant lastSyncedAt,
        Instant createdAt
) {

    public static MailAccountDto from(MailAccount a) {
        return new MailAccountDto(
                a.getId(),
                a.getLabel(),
                a.getImapHost(),
                a.getImapPort(),
                a.getUsername(),
                a.isUseSsl(),
                a.isActive(),
                a.getLastSyncedAt(),
                a.getCreatedAt()
        );
    }
}
