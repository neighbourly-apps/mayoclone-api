package com.mayoclone.dto;

import com.mayoclone.domain.Vendor;

import java.time.Instant;

/** Safe view of a vendor. Deliberately omits {@code imapPassword}. */
public record VendorDto(
        Long id,
        String restaurantName,
        String ownerEmail,
        String stationCode,
        String stationName,
        String phone,
        String gstin,
        String addressLine,
        String imapHost,
        int imapPort,
        String imapUsername,
        boolean useSsl,
        boolean active,
        Instant lastSyncedAt,
        Instant createdAt
) {

    public static VendorDto from(Vendor v) {
        return new VendorDto(
                v.getId(),
                v.getRestaurantName(),
                v.getOwnerEmail(),
                v.getStationCode(),
                v.getStationName(),
                v.getPhone(),
                v.getGstin(),
                v.getAddressLine(),
                v.getImapHost(),
                v.getImapPort(),
                v.getImapUsername(),
                v.isUseSsl(),
                v.isActive(),
                v.getLastSyncedAt(),
                v.getCreatedAt()
        );
    }
}
