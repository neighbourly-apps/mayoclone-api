package com.mayoclone.trains;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the default {@link TrainStatusProvider}. A future NTES/IRCTC-backed
 * provider registered as a bean will win via {@link ConditionalOnMissingBean}.
 */
@Configuration
public class TrainStatusConfig {

    @Bean
    @ConditionalOnMissingBean(TrainStatusProvider.class)
    public TrainStatusProvider trainStatusProvider() {
        return new UnavailableTrainStatusProvider();
    }
}
