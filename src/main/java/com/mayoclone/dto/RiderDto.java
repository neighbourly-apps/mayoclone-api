package com.mayoclone.dto;

import com.mayoclone.domain.Rider;

import java.time.Instant;

/** Read view of a delivery rider. */
public record RiderDto(Long id, String name, String phone, boolean active, Instant createdAt) {

    public static RiderDto from(Rider r) {
        return new RiderDto(r.getId(), r.getName(), r.getPhone(), r.isActive(), r.getCreatedAt());
    }
}
