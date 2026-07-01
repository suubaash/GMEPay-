package com.gme.pay.bff.alert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.contracts.events.OpsAlertPayload;

/**
 * A stored, UI-shaped view of one consumed {@code ops.alert} domain event. Mirrors the
 * canonical {@link OpsAlertPayload} plus a monotonic {@code seq} used for stable
 * newest-first ordering when two alerts share the same {@code occurredAt}.
 *
 * <p>{@code @JsonInclude(ALWAYS)} so null fields stay on the wire (consistent with the
 * other Ops read models).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OpsAlertView(
        long seq,
        String alertType,
        String severity,
        String subjectRef,
        String detail,
        String occurredAt) {

    /** Build a stored view from the canonical wire payload, stamping the sequence number. */
    static OpsAlertView from(long seq, OpsAlertPayload p) {
        return new OpsAlertView(
                seq, p.alertType(), p.severity(), p.subjectRef(), p.detail(), p.occurredAt());
    }
}
