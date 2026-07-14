package com.mayoclone.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The fulfilment lifecycle of an order, stored as a STRING column.
 *
 * <p>The happy path runs NEW -> ACCEPTED -> OUT_FOR_DELIVERY -> COMPLETED.
 * COMPLETED is the normal, fulfilled terminal. BILL_PENDING and CANCELLED are the
 * two EXCEPTION terminals ("different orders") — a delivered order whose bill is
 * still outstanding, and a cancelled order — reachable from any non-terminal state.
 * <pre>
 *   NEW              -> ACCEPTED            | BILL_PENDING, CANCELLED
 *   ACCEPTED         -> OUT_FOR_DELIVERY    | BILL_PENDING, CANCELLED
 *   OUT_FOR_DELIVERY -> COMPLETED           | BILL_PENDING, CANCELLED
 *   COMPLETED        -> (terminal)
 *   BILL_PENDING     -> (terminal)
 *   CANCELLED        -> (terminal)
 * </pre>
 * {@code deliveredAt} is stamped on entry to COMPLETED or BILL_PENDING (both mean
 * the food reached the passenger).
 */
public enum OrderStatus {
    NEW,
    ACCEPTED,
    OUT_FOR_DELIVERY,
    COMPLETED,
    BILL_PENDING,
    CANCELLED;

    /** The primary happy-path step. The exception targets are handled separately. */
    private static final Map<OrderStatus, Set<OrderStatus>> FORWARD = Map.of(
            NEW, EnumSet.of(ACCEPTED),
            ACCEPTED, EnumSet.of(OUT_FOR_DELIVERY),
            OUT_FOR_DELIVERY, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(OrderStatus.class),
            BILL_PENDING, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    /** The exception terminals, reachable from any non-terminal state. */
    private static final Set<OrderStatus> EXCEPTION_TARGETS = EnumSet.of(BILL_PENDING, CANCELLED);

    /** COMPLETED / BILL_PENDING / CANCELLED are end states — no further transitions. */
    public boolean isTerminal() {
        return this == COMPLETED || this == BILL_PENDING || this == CANCELLED;
    }

    /** True if the food reached the passenger (a fulfilled delivery). */
    public boolean isDelivered() {
        return this == COMPLETED || this == BILL_PENDING;
    }

    /** True if a move from {@code this} to {@code target} is a legal lifecycle transition. */
    public boolean canTransitionTo(OrderStatus target) {
        if (target == null || target == this) {
            return false;
        }
        // Any non-terminal state may be moved to an exception terminal (bill-pending / cancelled).
        if (EXCEPTION_TARGETS.contains(target)) {
            return !isTerminal();
        }
        return FORWARD.getOrDefault(this, Set.of()).contains(target);
    }
}
