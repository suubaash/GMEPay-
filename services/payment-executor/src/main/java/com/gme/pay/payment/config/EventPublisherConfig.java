package com.gme.pay.payment.config;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.LogEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the service's {@link EventPublisher}. {@code lib-events} is a pure contracts library with no
 * Spring annotations, so the consuming service owns the bean.
 *
 * <p>Phase-1 default is the no-infra {@link LogEventPublisher} (emits each event at INFO). Guarded by
 * {@link ConditionalOnMissingBean} so an outbox→Kafka publisher (lib-events-kafka) can supersede it at
 * integration without touching callers or this class.
 */
@Configuration
public class EventPublisherConfig {

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher eventPublisher() {
        return new LogEventPublisher();
    }
}
