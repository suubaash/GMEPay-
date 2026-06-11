package com.gme.pay.changerequest;

/**
 * Transition events that drive the {@link ChangeRequestState} machine.
 *
 * <p>Each event names the legitimate edge it triggers. The state machine is
 * configured (in {@link ChangeRequestStateMachineConfig}) so that an event
 * received in the wrong source state is ignored — no transition fires and the
 * caller can detect this by re-reading the current state. The service layer in
 * each adopting module turns that into a clear error to the operator (e.g. "you
 * cannot approve a request that is still in DRAFT").
 */
public enum ChangeRequestEvent {
    /** DRAFT → PROPOSED: maker submits the draft for checker review. */
    SUBMIT,
    /** PROPOSED → APPROVED: checker approves (with the DB-enforced 4-eyes check). */
    APPROVE,
    /** APPROVED → APPLIED: aggregate-level apply, in the same txn as audit_log write. */
    APPLY,
    /** {@code *} → REJECTED: terminal rejection with a reason. */
    REJECT
}
