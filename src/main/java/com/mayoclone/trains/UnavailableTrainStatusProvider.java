package com.mayoclone.trains;

/**
 * Default {@link TrainStatusProvider}: no live integration wired, so every query
 * returns {@link TrainStatus#unavailable()}. Registered by {@link TrainStatusConfig}
 * only when no other {@link TrainStatusProvider} bean exists, so a real provider
 * transparently wins.
 */
public class UnavailableTrainStatusProvider implements TrainStatusProvider {

    @Override
    public TrainStatus status(String trainNumber) {
        return TrainStatus.unavailable();
    }
}
