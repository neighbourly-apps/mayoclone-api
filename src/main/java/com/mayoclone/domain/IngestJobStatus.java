package com.mayoclone.domain;

/**
 * Lifecycle of a durable {@link IngestJob}.
 *
 * <pre>
 *   PENDING ──claim()──▶ RUNNING ──complete()──▶ DONE
 *      ▲                    │
 *      │                    ├──fail() & attempts &lt; max──▶ PENDING (backoff)
 *      │                    └──fail() & attempts ≥ max──▶ DEAD (dead-letter)
 *      └────────────── reapStuck() (crash recovery) ───────┘
 * </pre>
 */
public enum IngestJobStatus {
    /** Runnable once {@code run_after} arrives. */
    PENDING,
    /** Claimed by a worker; {@code locked_by}/{@code locked_at} are set. */
    RUNNING,
    /** Handled successfully — terminal. */
    DONE,
    /** Last attempt failed but retries remain (transient state; row is re-scheduled to PENDING). */
    FAILED,
    /** Retries exhausted — terminal dead-letter. */
    DEAD
}
