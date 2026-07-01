package com.gme.pay.bff.client;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

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
 * <p><b>Two write modes.</b>
 * <ul>
 *   <li>{@link #record} is <b>best-effort</b>: a failed write is logged and the delegated
 *       action still proceeds. Appropriate for pure reads / low-stakes surfaces.</li>
 *   <li>{@link #recordDurable} is <b>fail-closed</b>: it throws
 *       {@link AuditWriteException} if the record could not be durably persisted, so a
 *       money/state-affecting operator action can be blocked when it has no audit trail.
 *       No money-affecting action without a durable audit record.</li>
 * </ul>
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
     * Durably record one operator action BEFORE the caller delegates a money/state-affecting
     * action. Returns the persisted record on success; throws {@link AuditWriteException}
     * if the write could not be durably persisted — the caller MUST NOT proceed with the
     * action in that case (fail closed: no durable audit ⇒ no privileged action).
     *
     * @throws AuditWriteException when the record could not be durably persisted
     */
    OperatorActionRecord recordDurable(String action, String target, String actor, String reason)
            throws AuditWriteException;

    /**
     * Thrown by {@link #recordDurable} when a money-affecting operator action's audit
     * record could not be durably written; the action must be blocked. Mapped to HTTP
     * 500 so the operator sees the action did not run (no durable audit ⇒ no action).
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    class AuditWriteException extends RuntimeException {
        public AuditWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
