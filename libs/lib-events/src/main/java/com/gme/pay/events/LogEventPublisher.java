package com.gme.pay.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Default no-Spring {@link EventPublisher} implementation that simply emits each
 * published event at INFO level.
 *
 * <p>Kept deliberately free of Spring annotations — {@code lib-events} is a pure
 * contracts library, so wiring this as a bean is the responsibility of the
 * consuming service (e.g. via an {@code @Bean} method in a service-local
 * {@code @Configuration}, typically guarded by {@code @ConditionalOnMissingBean}).
 *
 * <p>At integration phase this is superseded by an outbox-backed Kafka publisher
 * without any caller change.
 */
public final class LogEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogEventPublisher.class);

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        log.info("domain event: type={} aggregateId={} occurredAt={}",
                event.eventType(), event.aggregateId(), event.occurredAt());
    }
}
