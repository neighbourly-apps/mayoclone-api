package com.mayoclone.dto;

import com.mayoclone.trains.TrainStatus;

/** Response body for {@code GET /api/trains/{trainNumber}/status}. */
public record TrainStatusResponse(
        String trainNumber,
        boolean available,
        String lastStation,
        String nextStation,
        Integer delayMinutes,
        Integer etaMinutes,
        String source
) {

    public static TrainStatusResponse of(String trainNumber, TrainStatus s) {
        return new TrainStatusResponse(
                trainNumber,
                s.available(),
                s.lastStation(),
                s.nextStation(),
                s.delayMinutes(),
                s.etaMinutes(),
                s.source());
    }
}
