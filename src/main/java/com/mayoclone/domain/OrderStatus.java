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
 *   ACCEPTED         -> OUT_FOR_DELIVERY, CANCELLED
 *   OUT_FOR_DELIVERY -> BILL_PENDING, CANCELLED
 *   BILL_PENDING     -> (terminal)
 *   CANCELLED        -> (terminal)
 * </pre>
 * In short: follow the chain forward, and any non-terminal state may be CANCELLED.
 * BILL_PENDING is the "delivered, bill pending" point — {@code deliveredAt} is
 * stamped on entry to it.
 */
public enum OrderStatus {
    NEW,
    ACCEPTED,
    OUT_FOR_DELIVERY,
    BILL_PENDING,
    CANCELLED;

    /** Forward (non-cancel) transitions. CANCELLED is handled by the any-non-terminal rule. */
    private static final Map<OrderStatus, Set<OrderStatus>> FORWARD = Map.of(
            NEW, EnumSet.of(ACCEPTED),
            ACCEPTED, EnumSet.of(OUT_FOR_DELIVERY),
            OUT_FOR_DELIVERY, EnumSet.of(BILL_PENDING),
            BILL_PENDING, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    /** BILL_PENDING / CANCELLED are end states — no further transitions. */
    public boolean isTerminal() {
        return this == BILL_PENDING || this == CANCELLED;
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
