package com.gme.pay.bff.client;

import java.time.Instant;

/**
 * WRITE side of the operator-action audit trail — the who/what/when/reason record
 * every audited Ops operator action (pause/resume/maintenance/suspend/unsuspend,
 * transaction resolve, webhook replay, recon rerun) writes BEFORE delegating to the
 * upstream service. Read side stays {@link AuditClient} (paginated timeline).
 *
 * <p><b>Ownership.</b> The audit log is owned by {@code auth-identity} (Phase 4). The
 * production implementation POSTs to auth-identity's operator-action audit endpoint;
 * the Phase-1 default {@link com.gme.pay.bff.client.stub.StubOperatorActionAuditClient}
 * captures records in memory so the BFF boots standalone and controller tests can
 * assert the record was written without an operating auth service.
 *
 * <p><b>Best-effort, never blocks the action.</b> A failed audit write is logged and
 * the delegated action still proceeds — the operator action is the primary intent and
 * the audit record is a durable side-effect, not a gate. (A future hard-audit mode
 * that fails-closed is an outstanding ask.)
 */
public interface OperatorActionAuditClient {

    /**
     * Record one operator action. Returns the persisted entry (with server-assigned
     * id + timestamp) or a best-effort local echo when the write could not be
     * durably persisted. Never throws — audit is a side-effect, not a gate.
     *
     * @param action dotted-resource action verb, e.g. {@code ops.pause},
     *               {@code transaction.resolve}, {@code webhook.replay},
     *               {@code settlement.recon.rerun}
     * @param target the entity id the action operated on (partner code / txn ref /
     *               delivery id / "system")
     * @param actor  the operator principal (from the request; "unknown" when absent)
     * @param reason free-text operator-supplied reason; may be null
     */
    OperatorActionRecord record(String action, String target, String actor, String reason);

    /**
     * One persisted operator-action audit row. {@code at} is the instant the record
     * was written (server-assigned in production; deterministic-ish in the stub).
     */
    record OperatorActionRecord(
            String id,
            String action,
            String target,
            String actor,
            String reason,
            Instant at
    ) {}
}
