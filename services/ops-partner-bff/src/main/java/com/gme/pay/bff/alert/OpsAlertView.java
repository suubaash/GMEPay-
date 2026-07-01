package com.gme.pay.bff.alert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.contracts.events.OpsAlertPayload;

/**
 * A stored, UI-shaped view of one consumed {@code ops.alert} domain event. Mirrors the
 * canonical {@link OpsAlertPayload} plus a monotonic {@code seq} used for stable
 * newest-first ordering when two alerts share the same {@code occurredAt}, plus the
 * paging {@link Paging} record and the {@link Ack} acknowledgement state so the control
 * tower and {@code GET /v1/admin/ops/alerts} can show whether a human was paged and
 * whether the alert is still {@code open} or {@code acked}.
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
        String occurredAt,
        Paging paging,
        Ack ack) {

    /** Build a stored view from the canonical wire payload, stamping the sequence number. */
    static OpsAlertView from(long seq, OpsAlertPayload p) {
        return new OpsAlertView(
                seq, p.alertType(), p.severity(), p.subjectRef(), p.detail(), p.occurredAt(),
                null, null);
    }

    /** Copy with the paging record set/updated (paging is recorded after store). */
    public OpsAlertView withPaging(Paging p) {
        return new OpsAlertView(seq, alertType, severity, subjectRef, detail, occurredAt, p, ack);
    }

    /** Copy with the acknowledgement set (ack stops escalation). */
    public OpsAlertView withAck(Ack a) {
        return new OpsAlertView(seq, alertType, severity, subjectRef, detail, occurredAt, paging, a);
    }

    /** Convenience for the escalation scheduler: an alert is open until acked. */
    public boolean acked() {
        return ack != null;
    }

    /**
     * The paging delivery record stamped on the alert. {@code status} is one of
     * {@code DELIVERED} | {@code FAILED} | {@code SUPPRESSED} (deduped within the cooldown
     * window). {@code attempts} counts distinct paging attempts (incremented by escalation
     * re-pages); {@code lastAt} is the ISO-8601 instant of the last attempt.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Paging(String status, String channel, int attempts, String lastAt, String detail) {}

    /** The acknowledgement stamped on the alert; presence ⇒ {@code acked} (escalation stops). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Ack(String operator, String note, String at) {}
}
