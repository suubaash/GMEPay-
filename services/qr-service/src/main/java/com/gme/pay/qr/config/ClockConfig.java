package com.gme.pay.qr.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Supplies a {@link Clock} bean so time-dependent components (e.g. expiry sweep) are testable. */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
