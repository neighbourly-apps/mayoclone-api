package com.mayoclone.trains;

/**
 * Pluggable seam for live train running status. The default wiring is
 * {@link UnavailableTrainStatusProvider} (always {@code available=false}); a future
 * NTES/IRCTC-backed implementation can be dropped in as a {@code @Component} and
 * will replace the default (see {@code @ConditionalOnMissingBean}).
 */
public interface TrainStatusProvider {

    TrainStatus status(String trainNumber);
}
