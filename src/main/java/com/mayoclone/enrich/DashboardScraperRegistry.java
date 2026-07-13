package com.mayoclone.enrich;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Selects the {@link DashboardScraper} for a given provider key, discovered from all
 * {@link DashboardScraper} beans in the context (like {@code MailSourceRegistry}). A
 * provider with no registered scraper simply yields {@link Optional#empty()} — the
 * enrich job then treats it as a clean no-op.
 */
@Component
public class DashboardScraperRegistry {

    private final Map<String, DashboardScraper> byProvider = new HashMap<>();

    public DashboardScraperRegistry(List<DashboardScraper> scrapers) {
        for (DashboardScraper s : scrapers) {
            byProvider.put(s.provider(), s);
        }
    }

    public Optional<DashboardScraper> forProvider(String provider) {
        return Optional.ofNullable(provider == null ? null : byProvider.get(provider));
    }
}
