package com.gme.pay.txn.outbox;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;

import java.time.Instant;

/**
 * Domain event carrying the canonical {@link OpsAlertPayload} for an Operations alert raised by the
 * stuck/aged-transaction sweep. Drains via the existing outbox {@link com.gme.pay.events.EventPublisher}
 * to topic {@code gmepay.ops.alert} (from {@link OpsAlertPayload#EVENT_TYPE}); the local
 * {@code LoggingEventPublisher} logs it when no broker is configured.
 *
 * <p>The record components mirror {@link OpsAlertPayload} so the canonical event serializer emits the
 * exact camelCase wire shape. {@code aggregateId} = {@code subjectRef} (the txnRef the alert concerns),
 * so per-subject ordering is preserved in the outbox drain.
 */
public record OpsAlertEvent(
        String alertType,
        String severity,
        String subjectRef,
        String detail,
        String occurredAtIso,
        Instant occurredAt
) implements DomainEvent {

    /** Builds an alert event, stamping {@code occurredAt} = now and its ISO-8601 string form. */
    public static OpsAlertEvent of(String alertType, String severity,
                                   String subjectRef, String detail) {
        Instant now = Instant.now();
        return new OpsAlertEvent(alertType, severity, subjectRef, detail, now.toString(), now);
    }

    /** Maps to the canonical wire payload. */
    public OpsAlertPayload toPayload() {
        return new OpsAlertPayload(
                OpsAlertPayload.EVENT_TYPE, alertType, severity, subjectRef, detail, occurredAtIso);
    }

    /** {@code ops.alert} → topic {@code gmepay.ops.alert}. */
    @Override
    public String eventType() {
        return OpsAlertPayload.EVENT_TYPE;
    }

    @Override
    public String aggregateId() {
        return subjectRef;
    }
}
