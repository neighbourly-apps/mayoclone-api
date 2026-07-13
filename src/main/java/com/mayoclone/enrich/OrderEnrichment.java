package com.mayoclone.enrich;

/**
 * The fields a {@link DashboardScraper} was able to recover for an order from the
 * aggregator's vendor dashboard. Every field is nullable — a scraper fills only what
 * it found, and the enrich job back-fills ONLY the order fields that are currently
 * blank (it never clobbers a good parse from the email).
 *
 * @param passengerName the customer/passenger name (the primary target — emails omit it)
 * @param passengerPhone contact number, when the dashboard exposes it
 * @param coach          coach, when present
 * @param berth          berth/seat, when present
 * @param pnr            PNR, when present
 * @param deliverySlot   scheduled delivery time text, when present
 * @param trainNumber    train number, when present (emails have it, but back-fill if blank)
 * @param trainName      train name — IRCTC emails OMIT this; the dashboard has it (e.g. "MAHAKAUSHAL EXP")
 */
public record OrderEnrichment(
        String passengerName,
        String passengerPhone,
        String coach,
        String berth,
        String pnr,
        String deliverySlot,
        String trainNumber,
        String trainName
) {

    /** Convenience for the common case: only a name was recovered. */
    public static OrderEnrichment ofName(String passengerName) {
        return new OrderEnrichment(passengerName, null, null, null, null, null, null, null);
    }
}
