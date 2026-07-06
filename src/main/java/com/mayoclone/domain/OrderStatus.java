package com.mayoclone.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The fulfilment lifecycle of an order, stored as a STRING column.
 *
 * <p>Allowed forward transitions:
 * <pre>
 *   NEW              -> ACCEPTED, CANCELLED
 *   ACCEPTED         -> PREPARING, CANCELLED
 *   PREPARING        -> READY,     CANCELLED
 *   READY            -> OUT_FOR_DELIVERY, CANCELLED
 *   OUT_FOR_DELIVERY -> DELIVERED, UNDELIVERED, CANCELLED
 *   DELIVERED / UNDELIVERED / CANCELLED -> (terminal)
 * </pre>
 * In short: follow the chain forward, and any non-terminal state may be CANCELLED.
 */
public enum OrderStatus {
    NEW,
    ACCEPTED,
    PREPARING,
    READY,
    OUT_FOR_DELIVERY,
    DELIVERED,
    UNDELIVERED,
    CANCELLED;

    /** Forward (non-cancel) transitions. CANCELLED is handled by the any-non-terminal rule. */
    private static final Map<OrderStatus, Set<OrderStatus>> FORWARD = Map.of(
            NEW, EnumSet.of(ACCEPTED),
            ACCEPTED, EnumSet.of(PREPARING),
            PREPARING, EnumSet.of(READY),
            READY, EnumSet.of(OUT_FOR_DELIVERY),
            OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, UNDELIVERED),
            DELIVERED, EnumSet.noneOf(OrderStatus.class),
            UNDELIVERED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    /** DELIVERED / UNDELIVERED / CANCELLED are end states — no further transitions. */
    public boolean isTerminal() {
        return this == DELIVERED || this == UNDELIVERED || this == CANCELLED;
    }

    /** True if a move from {@code this} to {@code target} is a legal lifecycle transition. */
    public boolean canTransitionTo(OrderStatus target) {
        if (target == null || target == this) {
            return false;
        }
        // Any non-terminal state may be cancelled.
        if (target == CANCELLED) {
            return !isTerminal();
        }
        return FORWARD.getOrDefault(this, Set.of()).contains(target);
    }
}
