package com.mayoclone.enrich;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for the dashboard order-enrichment framework. The feature is OFF by
 * default ({@code mayoclone.enrich.enabled=false}) so out of the box nothing is enqueued
 * and the scraper (if ever invoked) is idle — zero regression to current behavior.
 * Mirrors {@code GmailPushProperties}.
 *
 * <p>The IRCTC eCatering endpoints, unlike the earlier placeholder, ship with WORKING
 * defaults pointing at the real partner API ({@code base-url} +
 * {@code login-path}/{@code order-path}); only {@code enabled} plus per-vendor
 * credentials are needed to go live.
 *
 * <ul>
 *   <li>{@code mayoclone.enrich.enabled} — master switch. BLANK/false ⇒ the ingestion
 *       pipeline never enqueues a DASHBOARD_ENRICH job and the handler no-ops.</li>
 *   <li>{@code mayoclone.enrich.engine} — the default provider key (default
 *       {@code IRCTC_ECATERING}); informational / future multi-engine routing.</li>
 *   <li>{@code mayoclone.enrich.min-interval-ms} — politeness floor between dashboard
 *       hits (a scraper may pace itself with this).</li>
 *   <li>{@code mayoclone.enrich.irctc.base-url} — fixed IRCTC eCatering API root
 *       (default {@code https://www.ecatering.irctc.co.in/api/v1}).</li>
 *   <li>{@code mayoclone.enrich.irctc.login-path} — path (relative to base-url) for the
 *       mobile+password login POST (default {@code /auth/user/login}).</li>
 *   <li>{@code mayoclone.enrich.irctc.order-path} — path (relative to base-url) for the
 *       order-detail GET; the token {@code {orderId}} is substituted with the external
 *       order id (default {@code /order/{orderId}}).</li>
 * </ul>
 */
@Component
public class EnrichmentProperties {

    /** Fixed IRCTC eCatering API root — used when {@code base-url} is blank. */
    public static final String DEFAULT_IRCTC_BASE_URL = "https://www.ecatering.irctc.co.in/api/v1";
    /** Login path (relative to base-url) — used when {@code login-path} is blank. */
    public static final String DEFAULT_IRCTC_LOGIN_PATH = "/auth/user/login";
    /** Order-detail path (relative to base-url) — used when {@code order-path} is blank. */
    public static final String DEFAULT_IRCTC_ORDER_PATH = "/order/{orderId}";

    private final boolean enabled;
    private final String engine;
    private final long minIntervalMs;

    private final String irctcBaseUrl;
    private final String irctcLoginPath;
    private final String irctcOrderPath;

    public EnrichmentProperties(
            @Value("${mayoclone.enrich.enabled:false}") boolean enabled,
            @Value("${mayoclone.enrich.engine:IRCTC_ECATERING}") String engine,
            @Value("${mayoclone.enrich.min-interval-ms:1500}") long minIntervalMs,
            @Value("${mayoclone.enrich.irctc.base-url:" + DEFAULT_IRCTC_BASE_URL + "}") String irctcBaseUrl,
            @Value("${mayoclone.enrich.irctc.login-path:" + DEFAULT_IRCTC_LOGIN_PATH + "}") String irctcLoginPath,
            @Value("${mayoclone.enrich.irctc.order-path:" + DEFAULT_IRCTC_ORDER_PATH + "}") String irctcOrderPath) {
        this.enabled = enabled;
        this.engine = engine;
        this.minIntervalMs = minIntervalMs;
        this.irctcBaseUrl = irctcBaseUrl;
        this.irctcLoginPath = irctcLoginPath;
        this.irctcOrderPath = irctcOrderPath;
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

    /** IRCTC API root; falls back to {@link #DEFAULT_IRCTC_BASE_URL} when blank. */
    public String getIrctcBaseUrl() {
        return isBlank(irctcBaseUrl) ? DEFAULT_IRCTC_BASE_URL : irctcBaseUrl.trim();
    }

    /** IRCTC login path; falls back to {@link #DEFAULT_IRCTC_LOGIN_PATH} when blank. */
    public String getIrctcLoginPath() {
        return isBlank(irctcLoginPath) ? DEFAULT_IRCTC_LOGIN_PATH : irctcLoginPath.trim();
    }

    /** IRCTC order-detail path template; falls back to {@link #DEFAULT_IRCTC_ORDER_PATH} when blank. */
    public String getIrctcOrderPath() {
        return isBlank(irctcOrderPath) ? DEFAULT_IRCTC_ORDER_PATH : irctcOrderPath.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
