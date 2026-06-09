package com.gme.pay.notify.config;

import com.gme.pay.notify.domain.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides a {@link Clock} bean (UTC) so that {@code WebhookReplayGuard} and
 * {@code WebhookSender} can be tested with a fixed clock without touching system time.
 *
 * <p>Also exposes the framework-agnostic {@link RetryPolicy} as a Spring bean so
 * the Phase-1 {@code WebhookPersistenceService} can consume it without annotating
 * the domain class.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RetryPolicy retryPolicy() {
        return new RetryPolicy();
    }
}
