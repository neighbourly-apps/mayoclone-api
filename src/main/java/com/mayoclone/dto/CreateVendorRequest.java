package com.mayoclone.dto;

import com.mayoclone.domain.MailSourceType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Vendor signup request.
 *
 * <p>{@code sourceType} selects how mail arrives (defaults to {@link MailSourceType#IMAP}):
 * <ul>
 *   <li>IMAP — {@code imapHost} + {@code imapPassword} are required (validated in the service).</li>
 *   <li>FORWARDING — no IMAP fields; the service mints a unique {@code ingestAddress}.</li>
 *   <li>GMAIL_OAUTH — created/attached via the Gmail OAuth flow, not this endpoint.</li>
 * </ul>
 * {@code imapUsername} defaults to {@code ownerEmail} when blank; {@code imapPort}
 * defaults to 993 and {@code useSsl} to true.
 */
public record CreateVendorRequest(
        @NotBlank String restaurantName,
        @NotBlank @Email String ownerEmail,
        // Station name is mandatory — it identifies the delivery station on invoices and
        // in the ops UI. stationCode + phone remain optional so connecting a mailbox stays
        // simple (email + app password).
        String stationCode,
        @NotBlank(message = "Station name is required") String stationName,
        String phone,
        String gstin,
        String addressLine,
        MailSourceType sourceType,
        String imapHost,
        @Min(1) @Max(65535) Integer imapPort,
        String imapUsername,
        String imapPassword,
        Boolean useSsl
) {
}
