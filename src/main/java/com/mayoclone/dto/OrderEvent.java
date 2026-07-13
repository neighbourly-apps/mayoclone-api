package com.mayoclone.dto;

/**
 * Real-time push payload delivered to {@code /user/queue/orders}.
 *
 * @param type  one of {@code NEW_ORDER}, {@code STATUS_CHANGED}, {@code ASSIGNED},
 *              {@code ENRICHED}
 * @param order the current full view of the affected order
 */
public record OrderEvent(String type, OrderDto order) {

    public static final String NEW_ORDER = "NEW_ORDER";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
    public static final String ASSIGNED = "ASSIGNED";
    /** An existing order was back-filled from the vendor dashboard (e.g. passenger name). */
    public static final String ENRICHED = "ENRICHED";
}
