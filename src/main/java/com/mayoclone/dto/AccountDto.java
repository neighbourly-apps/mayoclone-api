package com.mayoclone.dto;

import com.mayoclone.domain.Account;

import java.time.Instant;

/** Safe view of an account. NEVER exposes the password hash. */
public record AccountDto(
        Long id,
        String businessName,
        String email,
        String role,
        String status,
        String stationCode,
        String stationName,
        String gstin,
        String addressLine,
        String phone,
        boolean emailVerified,
        Instant createdAt
) {

    public static AccountDto from(Account a) {
        return new AccountDto(
                a.getId(),
                a.getBusinessName(),
                a.getEmail(),
                a.getRole(),
                a.getStatus(),
                a.getStationCode(),
                a.getStationName(),
                a.getGstin(),
                a.getAddressLine(),
                a.getPhone(),
                a.isEmailVerified(),
                a.getCreatedAt()
        );
    }
}
