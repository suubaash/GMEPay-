package com.gme.pay.settlement.outbox;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local {@link EventPublisher} fallback used by {@link OutboxConfig} when lib-events-kafka is not
 * active (no {@code spring.kafka.bootstrap-servers}). Logs the drained event; the broker transport
 * (KafkaEventPublisher) takes over under docker-compose without changing any callers.
 */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(DomainEvent event) {
        log.info("settlement outbox event: type={} aggregateId={} occurredAt={}",
                event.eventType(), event.aggregateId(), event.occurredAt());
    }
}
