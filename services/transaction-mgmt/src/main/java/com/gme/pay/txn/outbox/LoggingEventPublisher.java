package com.gme.pay.txn.outbox;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase-1 {@link EventPublisher} implementation.
 *
 * <p>Logs the event and records it in-memory.  At integration phase this bean is replaced
 * by an outbox-backed Kafka publisher without changing any callers.
 */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(DomainEvent event) {
        log.info("outbox event: type={} aggregateId={} occurredAt={}",
                event.eventType(), event.aggregateId(), event.occurredAt());
    }
}
