package com.gme.pay.scheme.zeropay.ops;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;

import java.time.Instant;

/**
 * In-memory {@link DomainEvent} carrying an ops alert raised inside the ZeroPay adapter (gap <b>T3-4</b>).
 *
 * <p>Identical in shape to settlement-reconciliation's {@code ReconAlertEvent} so both services put the same
 * bytes on the same topic. {@link #eventType()} is {@link OpsAlertPayload#EVENT_TYPE} ({@code "ops.alert"}),
 * from which {@code KafkaEventPublisher} derives the topic {@code gmepay.ops.alert}; {@link #aggregateId()}
 * is the subject ref and must be non-blank or the Kafka transport rejects the event.
 */
public record ZpOpsAlertEvent(OpsAlertPayload payload) implements DomainEvent {

    @Override
    public String eventType() {
        return payload.eventType();
    }

    @Override
    public String aggregateId() {
        return payload.subjectRef();
    }

    @Override
    public Instant occurredAt() {
        return Instant.parse(payload.occurredAt());
    }
}
