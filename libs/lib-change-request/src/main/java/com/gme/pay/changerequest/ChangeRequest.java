package com.gme.pay.changerequest;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * The 4-eyes maker-checker primitive per ADR-008.
 *
 * <p>A {@code ChangeRequest} captures a proposed mutation to a regulated
 * aggregate (partner, partner_bank_account, rule, fee schedule, etc.) and walks
 * a Spring State Machine from {@link ChangeRequestState#DRAFT DRAFT} →
 * {@link ChangeRequestState#PROPOSED PROPOSED} → {@link ChangeRequestState#APPROVED APPROVED}
 * → {@link ChangeRequestState#APPLIED APPLIED}, with a terminal
 * {@link ChangeRequestState#REJECTED REJECTED} branch.
 *
 * <p>The corresponding DB table (owned per-service: config-registry's
 * {@code change_request}, auth-identity's, etc.) carries the same shape plus a
 * {@code CHECK (proposed_by IS DISTINCT FROM approved_by)} constraint so the
 * 4-eyes invariant is enforced in the database, not only in Java. A partial
 * exception lets system-driven proposals (e.g. auto-suspend on prefunding
 * breach) close the loop without a human checker — both columns may equal
 * {@code 'system'}.
 *
 * <p>Only {@link ChangeRequestState#APPLIED} mutates the underlying aggregate,
 * in the same transaction as the audit_log write (ADR-007). All other
 * transitions are paperwork.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code id} — surrogate BIGSERIAL, server-assigned on first persist.</li>
 *   <li>{@code aggregateType} — the kind of aggregate being mutated
 *       ({@code "partner"}, {@code "partner_bank_account"}, ...). Strings rather
 *       than an enum so new aggregates can be added without bumping this lib.</li>
 *   <li>{@code aggregateId} — the natural key of the row being changed. For the
 *       partner aggregate during Slice 1 this is the {@code partner_code}
 *       business code (V003).</li>
 *   <li>{@code state} — current FSM state.</li>
 *   <li>{@code proposedBy} / {@code proposedAt} — maker identity + submission time.</li>
 *   <li>{@code approvedBy} / {@code approvedAt} — checker identity + approval time.</li>
 *   <li>{@code rejectedReason} — mandatory free-text when state = REJECTED.</li>
 *   <li>{@code payloadJsonb} — the proposed new shape, persisted as JSONB so
 *       schema evolution does not require change_request column additions.</li>
 *   <li>{@code appliesToFieldSet} — which fields this change is allowed to mutate.
 *       The {@code apply} path is expected to ignore fields outside this set.
 *       Stored as {@code TEXT[]} in PostgreSQL; modelled here as {@code String[]}.</li>
 * </ul>
 *
 * <p>This record is immutable. The mutable JPA entity lives in each adopting
 * service ({@code com.gme.pay.registry.changerequest.ChangeRequestEntity} for
 * config-registry); the service layer converts to/from this record at the
 * persistence boundary.
 */
public record ChangeRequest(
        Long id,
        String aggregateType,
        String aggregateId,
        ChangeRequestState state,
        String proposedBy,
        Instant proposedAt,
        String approvedBy,
        Instant approvedAt,
        String rejectedReason,
        String payloadJsonb,
        String[] appliesToFieldSet) {

    public ChangeRequest {
        Objects.requireNonNull(aggregateType, "aggregateType required");
        Objects.requireNonNull(aggregateId, "aggregateId required");
        Objects.requireNonNull(state, "state required");
        // Defensive copy of the array field so record callers cannot mutate
        // the internal representation via the constructor argument. Returning
        // the same protected reference from the accessor keeps the record
        // "effectively immutable" within this library.
        if (appliesToFieldSet != null) {
            appliesToFieldSet = appliesToFieldSet.clone();
        }
    }

    /** Convenience for building a fresh DRAFT proposal. */
    public static ChangeRequest draft(String aggregateType, String aggregateId,
                                      String proposedBy, String payloadJsonb,
                                      String[] appliesToFieldSet) {
        return new ChangeRequest(
                null,
                aggregateType,
                aggregateId,
                ChangeRequestState.DRAFT,
                proposedBy,
                Instant.now(),
                null,
                null,
                null,
                payloadJsonb,
                appliesToFieldSet);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChangeRequest other)) return false;
        return Objects.equals(id, other.id)
                && Objects.equals(aggregateType, other.aggregateType)
                && Objects.equals(aggregateId, other.aggregateId)
                && state == other.state
                && Objects.equals(proposedBy, other.proposedBy)
                && Objects.equals(proposedAt, other.proposedAt)
                && Objects.equals(approvedBy, other.approvedBy)
                && Objects.equals(approvedAt, other.approvedAt)
                && Objects.equals(rejectedReason, other.rejectedReason)
                && Objects.equals(payloadJsonb, other.payloadJsonb)
                && Arrays.equals(appliesToFieldSet, other.appliesToFieldSet);
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(id, aggregateType, aggregateId, state, proposedBy,
                proposedAt, approvedBy, approvedAt, rejectedReason, payloadJsonb);
        h = 31 * h + Arrays.hashCode(appliesToFieldSet);
        return h;
    }

    @Override
    public String toString() {
        return "ChangeRequest{id=" + id
                + ", aggregateType=" + aggregateType
                + ", aggregateId=" + aggregateId
                + ", state=" + state
                + ", proposedBy=" + proposedBy
                + ", approvedBy=" + approvedBy
                + ", appliesToFieldSet=" + Arrays.toString(appliesToFieldSet)
                + "}";
    }
}
