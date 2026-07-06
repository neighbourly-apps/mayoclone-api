package com.mayoclone.domain;

/**
 * How an order entered the system.
 *
 * <ul>
 *   <li>{@link #ONLINE} — ingested from an aggregator confirmation email; carries an aggregator.</li>
 *   <li>{@link #DIRECT} — manually entered (walk-in / phone); has no aggregator.</li>
 * </ul>
 */
public enum OrderType {
    ONLINE,
    DIRECT
}
