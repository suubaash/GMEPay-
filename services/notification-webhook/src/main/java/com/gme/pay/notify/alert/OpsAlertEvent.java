package com.gme.pay.notify.alert;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * {@link DomainEvent} adapter that carries an {@link OpsAlertPayload} onto the
 * {@link com.gme.pay.events.EventPublisher} seam. Its {@code eventType()} is the
 * canonical {@code ops.alert}, so the Kafka publisher routes it to topic
 * {@code gmepay.ops.alert}; {@code aggregateId()} is the alert subjectRef (partner
 * id or {@code "global"}), giving per-subject ordering.
 *
 * <p>The payload record is {@code @JsonUnwrapped} so the wire JSON is a flat
 * {@code OpsAlertPayload} (eventType/alertType/severity/subjectRef/detail/occurredAt),
 * matching {@code PaymentApprovedPayload}'s flat convention — the publisher then
 * (re)asserts the contract fields eventType/aggregateId/occurredAt.
 */
public final class OpsAlertEvent implements DomainEvent {

    @JsonUnwrapped
    private final OpsAlertPayload payload;

    public OpsAlertEvent(OpsAlertPayload payload) {
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public OpsAlertPayload getPayload() {
        return payload;
    }

    @Override
    public String eventType() {
        // Route to gmepay.ops.alert regardless of the payload's own eventType field.
        return OpsAlertPayload.EVENT_TYPE;
    }

    @Override
    public String aggregateId() {
        String subjectRef = payload.subjectRef();
        return (subjectRef == null || subjectRef.isBlank()) ? "global" : subjectRef;
    }

    @Override
    public Instant occurredAt() {
        try {
            return payload.occurredAt() == null ? Instant.EPOCH : Instant.parse(payload.occurredAt());
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
