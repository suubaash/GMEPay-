package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Canonical read DTO for a {@code change_request} row — the JSON shape every
 * consumer (ops-partner-bff, admin-ui) deserializes when reading the 4-eyes
 * approval queue or a single change request.
 *
 * <p>Lives in {@code lib-api-contracts} so the BFF and any future portal UI
 * can share the same record without copying fields. The matching write
 * operations (approve / reject) carry their own thin request bodies defined
 * inline on the controller to avoid polluting this module with mutable DTOs.
 *
 * <h2>Field roster</h2>
 * <ul>
 *   <li>{@code id} — surrogate BIGINT, server-assigned on first persist.</li>
 *   <li>{@code aggregateType} — the kind of aggregate being mutated
 *       (e.g. {@code "partner"}).</li>
 *   <li>{@code aggregateId} — natural key of the aggregate row being changed
 *       (e.g. {@code "GMEREMIT"}).</li>
 *   <li>{@code state} — current FSM state name: one of DRAFT, PROPOSED, APPROVED,
 *       APPLIED, REJECTED. Carried as String so lib-api-contracts does not need to
 *       depend on lib-change-request's Spring State Machine stack.</li>
 *   <li>{@code proposedBy} / {@code proposedAt} — maker identity + submit time.</li>
 *   <li>{@code approvedBy} / {@code approvedAt} — checker identity + decision time.
 *       Non-null once state ≥ APPROVED or = REJECTED.</li>
 *   <li>{@code rejectedReason} — mandatory free-text when state = REJECTED;
 *       {@code null} for every other state.</li>
 *   <li>{@code payloadJson} — proposed new shape, serialised as JSON text.
 *       Consumers may parse this further but the BFF passes it through opaquely.</li>
 *   <li>{@code appliesTo} — array of field names this change is scoped to;
 *       {@code null} or empty means "all fields on the aggregate". Serialised as
 *       a JSON array so the Admin UI can show a diff summary.</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI uses the field being present to distinguish "not yet set" from
 * "server omitted it".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChangeRequestView(
        Long id,
        String aggregateType,
        String aggregateId,
        String state,
        String proposedBy,
        Instant proposedAt,
        String approvedBy,
        Instant approvedAt,
        String rejectedReason,
        String payloadJson,
        String[] appliesTo) {

}
