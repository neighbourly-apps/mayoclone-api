package com.mayoclone.trains;

/**
 * Live-ish running status of a train, as returned by a {@link TrainStatusProvider}.
 * All positional fields except {@code available} and {@code source} are nullable
 * (a provider may not know them, or the train may be unavailable).
 *
 * @param available     whether a status could be resolved at all
 * @param lastStation   code of the last crossed station (nullable)
 * @param nextStation   code of the next station (nullable)
 * @param delayMinutes  running delay in minutes (nullable)
 * @param etaMinutes    ETA to the queried context in minutes (nullable)
 * @param source        identifier of the data source ("none" when unavailable)
 */
public record TrainStatus(
        boolean available,
        String lastStation,
        String nextStation,
        Integer delayMinutes,
        Integer etaMinutes,
        String source
) {

    /** The canonical "no data" status: not available, source "none". */
    public static TrainStatus unavailable() {
        return new TrainStatus(false, null, null, null, null, "none");
    }
}
