package com.gme.pay.settlement.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;

import java.time.Instant;

/**
 * In-memory {@link DomainEvent} for a reconciliation-break ops alert. Carries the canonical
 * {@link OpsAlertPayload} (alertType {@code RECON_BREAK}) that goes on the wire, and adapts it to the
 * Spring-free {@link DomainEvent} seam so it drains through the same {@link com.gme.pay.events.EventPublisher}
 * transport the rest of the service uses.
 *
 * <p>{@link #eventType()} is {@link OpsAlertPayload#EVENT_TYPE} ({@code "ops.alert"}) — the
 * KafkaEventPublisher derives the topic {@code gmepay.ops.alert} from it. {@link #aggregateId()} is the
 * subject batchId (must be non-blank or the Kafka transport rejects it).
 */
public record ReconAlertEvent(OpsAlertPayload payload) implements DomainEvent {

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
