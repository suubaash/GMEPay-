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
 * <p><b>Implementations, and which one is the default.</b>
 * <ul>
 *   <li>{@link com.gme.pay.bff.client.db.DbOperatorActionAuditClient} — <b>the default</b>
 *       ({@code gmepay.operator-action-audit.client=db}, also selected when the property is absent).
 *       Writes the durable {@code operator_action_audit} table (Flyway V002) this service owns.</li>
 *   <li>{@link com.gme.pay.bff.client.rest.RestOperatorActionAuditClient} — {@code rest}. POSTs to
 *       {@code /v1/audit/operator-actions}, <b>an endpoint that does not exist in this repo yet</b>;
 *       read that class before selecting it.</li>
 *   <li>{@link com.gme.pay.bff.client.stub.StubOperatorActionAuditClient} — {@code stub}, opt-in only
 *       and {@code WARN}ing at construction. It is not an audit trail: per-JVM ids that collide across
 *       replicas, lost on restart, and {@link #recordDurable} cannot fail. It was nevertheless the live
 *       bean in every environment until this commit, because it carried {@code matchIfMissing = true}
 *       and nothing set the selector.</li>
 * </ul>
 * An unrecognised selector value leaves <b>no</b> bean, so the service refuses to start rather than
 * silently degrading (the T1-1 shape).
 *
 * <p><b>Where the regulator-grade log lives.</b> config-registry owns the hash-chained
 * {@code audit_log} (read at {@code GET /v1/audit}, with chain verification) and remains the eventual
 * home for these rows. The table behind the default implementation is flat and append-only: it proves
 * what an operator did, not that nobody edited it afterwards.
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
