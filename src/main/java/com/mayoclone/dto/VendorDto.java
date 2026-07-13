package com.mayoclone.dto;

import com.mayoclone.domain.Vendor;

import java.time.Instant;

/**
 * Safe view of a vendor. Deliberately omits all secrets ({@code imapPassword},
 * {@code oauthRefreshToken}). {@code ingestAddress} is surfaced so the UI can show
 * "forward your aggregator mail here" for FORWARDING vendors.
 */
public record VendorDto(
        Long id,
        String restaurantName,
        String ownerEmail,
        String stationCode,
        String stationName,
        String phone,
        String gstin,
        String addressLine,
        String sourceType,
        String ingestAddress,
        String oauthEmail,
        Instant oauthConnectedAt,
        String imapHost,
        int imapPort,
        String imapUsername,
        boolean useSsl,
        boolean active,
        Instant lastSyncedAt,
        String lastSyncError,
        Instant nextPollAt,
        Instant createdAt,
        // Dashboard order-enrichment credential status ("NEW"/"ACTIVE"/"NEEDS_REAUTH"/
        // "DISABLED"), or null when no dashboard login is configured. NEVER a secret —
        // just enough for the UI to show a badge.
        String dashboardStatus
) {

    public static VendorDto from(Vendor v) {
        return from(v, null);
    }

    public static VendorDto from(Vendor v, String dashboardStatus) {
        return new VendorDto(
                v.getId(),
                v.getRestaurantName(),
                v.getOwnerEmail(),
                v.getStationCode(),
                v.getStationName(),
                v.getPhone(),
                v.getGstin(),
                v.getAddressLine(),
                v.getSourceType() == null ? null : v.getSourceType().name(),
                v.getIngestAddress(),
                v.getOauthEmail(),
                v.getOauthConnectedAt(),
                v.getImapHost(),
                v.getImapPort(),
                v.getImapUsername(),
                v.isUseSsl(),
                v.isActive(),
                v.getLastSyncedAt(),
                v.getLastSyncError(),
                v.getNextPollAt(),
                v.getCreatedAt(),
                dashboardStatus
        );
    }
}
