package com.mayoclone.enrich;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for the dashboard order-enrichment framework. The feature is OFF by
 * default ({@code mayoclone.enrich.enabled=false}) and EVERY provider endpoint/field
 * ships BLANK, so out of the box nothing is enqueued and a scraper (if ever invoked)
 * is idle — zero regression to current behavior. Mirrors {@code GmailPushProperties}.
 *
 * <ul>
 *   <li>{@code mayoclone.enrich.enabled} — master switch. BLANK/false ⇒ the ingestion
 *       pipeline never enqueues a DASHBOARD_ENRICH job and the handler no-ops.</li>
 *   <li>{@code mayoclone.enrich.engine} — the default provider key (default
 *       {@code IRCTC_ECATERING}); informational / future multi-engine routing.</li>
 *   <li>{@code mayoclone.enrich.min-interval-ms} — politeness floor between dashboard
 *       hits (a scraper may pace itself with this).</li>
 *   <li>{@code mayoclone.enrich.irctc.login-url} — POST target for username+password
 *       login. BLANK ⇒ the IRCTC scraper cannot log in and returns ENGINE_ERROR.</li>
 *   <li>{@code mayoclone.enrich.irctc.order-url} — order-lookup URL; the token
 *       {@code {orderId}} is substituted with the external order id. BLANK ⇒ NOT_FOUND.</li>
 *   <li>{@code mayoclone.enrich.irctc.name-json-path} — dotted path to the name in the
 *       lookup JSON. BLANK ⇒ the scraper falls back to {@code customer.fullName}.</li>
 * </ul>
 */
@Component
public class EnrichmentProperties {

    private final boolean enabled;
    private final String engine;
    private final long minIntervalMs;

    private final String irctcLoginUrl;
    private final String irctcOrderUrl;
    private final String irctcNameJsonPath;

    public EnrichmentProperties(
            @Value("${mayoclone.enrich.enabled:false}") boolean enabled,
            @Value("${mayoclone.enrich.engine:IRCTC_ECATERING}") String engine,
            @Value("${mayoclone.enrich.min-interval-ms:1500}") long minIntervalMs,
            @Value("${mayoclone.enrich.irctc.login-url:}") String irctcLoginUrl,
            @Value("${mayoclone.enrich.irctc.order-url:}") String irctcOrderUrl,
            @Value("${mayoclone.enrich.irctc.name-json-path:}") String irctcNameJsonPath) {
        this.enabled = enabled;
        this.engine = engine;
        this.minIntervalMs = minIntervalMs;
        this.irctcLoginUrl = irctcLoginUrl;
        this.irctcOrderUrl = irctcOrderUrl;
        this.irctcNameJsonPath = irctcNameJsonPath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getEngine() {
        return engine;
    }

    public long getMinIntervalMs() {
        return minIntervalMs;
    }

    public String getIrctcLoginUrl() {
        return irctcLoginUrl;
    }

    public String getIrctcOrderUrl() {
        return irctcOrderUrl;
    }

    public String getIrctcNameJsonPath() {
        return irctcNameJsonPath;
    }
}
