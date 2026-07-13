package com.mayoclone.enrich;

import com.mayoclone.domain.Vendor;
import com.mayoclone.domain.VendorDashboardCredential;

/**
 * Pluggable strategy that looks one order up on an aggregator's vendor dashboard and
 * returns whatever fields it can recover. One implementation per dashboard provider,
 * selected by {@link #provider()} (mirrors {@code MailSource}/{@code MailSourceRegistry}).
 *
 * <p><b>Contract.</b>
 * <ul>
 *   <li>{@link #provider()} must equal {@code VendorDashboardCredential.provider} of the
 *       credentials it handles (e.g. {@code "IRCTC_ECATERING"}) and be unique across beans.</li>
 *   <li>{@link #fetch} must NOT perform an interactive login. When there is no valid
 *       cached session on {@code cred}, it logs in with the stored username/password
 *       (the portal has no CAPTCHA/OTP), caches the captured session token back onto
 *       {@code cred} (the caller persists it), then looks the order up.</li>
 *   <li>On an authentication/session rejection it returns
 *       {@link EnrichResult#sessionExpired()} — it does NOT throw for that.</li>
 *   <li>It MUST return a non-null {@link EnrichResult}; wrap all I/O so a network/parse
 *       failure surfaces as {@link EnrichResult#engineError()} (or the specific typed
 *       outcome) rather than a raw exception.</li>
 * </ul>
 */
public interface DashboardScraper {

    /** The {@code provider} key this scraper handles. Unique across beans. */
    String provider();

    /**
     * Look {@code externalOrderId} up on the dashboard and return the recoverable fields.
     * Never returns {@code null}.
     */
    EnrichResult fetch(Vendor vendor, VendorDashboardCredential cred, String externalOrderId);
}
