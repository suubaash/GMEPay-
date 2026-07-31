package com.gme.pay.scheme.zeropay.ops;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Broker-free {@link EventPublisher} fallback, selected by {@link ZpOpsAlertConfig} when
 * {@code spring.kafka.bootstrap-servers} is not set — so local, unit and CI boots need no Kafka.
 *
 * <p>Mirrors settlement-reconciliation's {@code LoggingEventPublisher}. It logs at <b>WARN</b>, not INFO,
 * because the only events this adapter publishes are ops alerts: an alert that reached nothing but a log
 * file is itself a condition worth seeing at default log levels.
 */
@Component
public class ZpLoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ZpLoggingEventPublisher.class);

    @Override
    public void publish(DomainEvent event) {
        log.warn("zeropay ops alert (LOG ONLY — no broker configured, so nothing was paged): "
                        + "type={} subject={} occurredAt={}",
                event.eventType(), event.aggregateId(), event.occurredAt());
    }
}
