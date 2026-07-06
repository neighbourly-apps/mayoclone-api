package com.mayoclone.ingest;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Selects the pull-based {@link MailSource} for a vendor by its
 * {@link MailSourceType}. {@link MailSourceType#FORWARDING} is push-only and has
 * no registered source, so {@link #forVendor} returns empty for it — a scheduled
 * sweep simply skips forwarding vendors (their mail arrives via the webhook).
 */
@Component
public class MailSourceRegistry {

    private final Map<MailSourceType, MailSource> byType = new EnumMap<>(MailSourceType.class);

    public MailSourceRegistry(List<MailSource> sources) {
        for (MailSource s : sources) {
            byType.put(s.type(), s);
        }
    }

    public Optional<MailSource> forVendor(Vendor vendor) {
        return Optional.ofNullable(byType.get(vendor.getSourceType()));
    }

    public Optional<MailSource> forType(MailSourceType type) {
        return Optional.ofNullable(byType.get(type));
    }
}
