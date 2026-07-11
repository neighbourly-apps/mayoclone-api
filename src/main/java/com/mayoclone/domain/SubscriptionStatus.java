package com.mayoclone.domain;

/**
 * Lifecycle of an {@link Account}'s billing subscription, stored enum-as-string.
 *
 * <ul>
 *   <li>{@code TRIALING} — inside the 14-day free trial (see {@code trialEndsAt}).</li>
 *   <li>{@code ACTIVE}   — paid; access until {@code currentPeriodEnd}.</li>
 *   <li>{@code EXPIRED}  — trial lapsed or paid period ended without renewal.</li>
 *   <li>{@code CANCELLED}— explicitly cancelled.</li>
 * </ul>
 */
public enum SubscriptionStatus {
    TRIALING,
    ACTIVE,
    EXPIRED,
    CANCELLED
}
