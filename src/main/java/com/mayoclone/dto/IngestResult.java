package com.mayoclone.dto;

/**
 * Outcome of an ingestion run.
 *
 * @param fetched   number of source messages examined
 * @param newOrders number of new orders actually stored (after dedup)
 */
public record IngestResult(int fetched, int newOrders) {

    public IngestResult plus(IngestResult other) {
        return new IngestResult(this.fetched + other.fetched, this.newOrders + other.newOrders);
    }

    public static IngestResult empty() {
        return new IngestResult(0, 0);
    }
}
