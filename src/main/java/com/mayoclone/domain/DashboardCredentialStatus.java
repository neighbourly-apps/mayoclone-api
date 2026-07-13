package com.mayoclone.domain;

/**
 * Lifecycle of a {@link VendorDashboardCredential} used for dashboard order
 * enrichment.
 *
 * <ul>
 *   <li>{@link #NEW} — credentials stored, never yet used for a successful fetch.</li>
 *   <li>{@link #ACTIVE} — last enrichment fetch (login + lookup) succeeded.</li>
 *   <li>{@link #NEEDS_REAUTH} — the stored username/password no longer authenticate
 *       (login failed / session rejected); an operator must re-enter them. The enrich
 *       job SKIPS credentials in this state so we never hammer a dead login.</li>
 *   <li>{@link #DISABLED} — operator turned enrichment off for this vendor. Skipped.</li>
 * </ul>
 */
public enum DashboardCredentialStatus {
    NEW,
    ACTIVE,
    NEEDS_REAUTH,
    DISABLED
}
