package com.mayoclone.enrich;

/**
 * The typed result of one dashboard enrichment attempt. Drives how the enrich job
 * reacts (see {@code DashboardEnrichJobHandler}):
 *
 * <ul>
 *   <li>{@link #FOUND} — the order was located and at least a name recovered → fill blanks.</li>
 *   <li>{@link #NOT_FOUND} — dashboard had nothing to add → leave the order as-is, DONE
 *       (do not hammer the portal).</li>
 *   <li>{@link #SESSION_EXPIRED} — login/session was rejected → mark the credential
 *       NEEDS_REAUTH, DONE.</li>
 *   <li>{@link #RATE_LIMITED} — portal throttled us (429) → THROW so the queue retries
 *       with backoff.</li>
 *   <li>{@link #ENGINE_ERROR} — transient/unexpected engine failure → THROW so the queue
 *       retries with backoff.</li>
 * </ul>
 */
public enum EnrichOutcome {
    FOUND,
    NOT_FOUND,
    SESSION_EXPIRED,
    RATE_LIMITED,
    ENGINE_ERROR
}
