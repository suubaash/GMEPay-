package com.gme.pay.notify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides a {@link Clock} bean (UTC) so that {@code WebhookReplayGuard} and
 * {@code WebhookSender} can be tested with a fixed clock without touching system time.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
