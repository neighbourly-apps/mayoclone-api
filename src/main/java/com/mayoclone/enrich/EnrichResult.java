package com.mayoclone.enrich;

/**
 * A {@link DashboardScraper#fetch} result: an {@link EnrichOutcome} plus the
 * {@link OrderEnrichment} payload (non-null only for {@link EnrichOutcome#FOUND}).
 * Scrapers must NEVER return {@code null}; use the static factories.
 */
public record EnrichResult(EnrichOutcome outcome, OrderEnrichment enrichment) {

    public static EnrichResult found(OrderEnrichment enrichment) {
        return new EnrichResult(EnrichOutcome.FOUND, enrichment);
    }

    public static EnrichResult notFound() {
        return new EnrichResult(EnrichOutcome.NOT_FOUND, null);
    }

    public static EnrichResult sessionExpired() {
        return new EnrichResult(EnrichOutcome.SESSION_EXPIRED, null);
    }

    public static EnrichResult rateLimited() {
        return new EnrichResult(EnrichOutcome.RATE_LIMITED, null);
    }

    public static EnrichResult engineError() {
        return new EnrichResult(EnrichOutcome.ENGINE_ERROR, null);
    }

    public boolean isFound() {
        return outcome == EnrichOutcome.FOUND && enrichment != null;
    }
}
