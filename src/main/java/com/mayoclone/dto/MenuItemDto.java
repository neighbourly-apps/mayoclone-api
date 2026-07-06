package com.mayoclone.dto;

import com.mayoclone.domain.MenuItem;

import java.math.BigDecimal;
import java.time.Instant;

/** Tenant-scoped view of a menu item. */
public record MenuItemDto(
        Long id,
        String name,
        String category,
        BigDecimal price,
        boolean available,
        Instant createdAt
) {

    public static MenuItemDto from(MenuItem m) {
        return new MenuItemDto(
                m.getId(),
                m.getName(),
                m.getCategory(),
                m.getPrice(),
                m.isAvailable(),
                m.getCreatedAt()
        );
    }
}
