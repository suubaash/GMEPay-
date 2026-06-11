package com.gme.pay.changerequest;

/**
 * Lifecycle states for a {@link ChangeRequest} per ADR-008.
 *
 * <p>Allowed transitions (enforced by the Spring State Machine wired in
 * {@link ChangeRequestStateMachineConfig}):
 * <ul>
 *   <li>{@link #DRAFT} → {@link #PROPOSED}: the maker submits the draft for review.</li>
 *   <li>{@link #PROPOSED} → {@link #APPROVED}: a different operator (the checker)
 *       approves. The DB CHECK constraint forbids {@code proposed_by = approved_by}
 *       except for the {@code (system, system)} carve-out.</li>
 *   <li>{@link #APPROVED} → {@link #APPLIED}: the change is applied to the
 *       underlying aggregate in the same transaction as the audit_log write.</li>
 *   <li>{@code *} → {@link #REJECTED}: terminal rejection from any non-terminal
 *       state (DRAFT / PROPOSED / APPROVED). A rejection reason is mandatory.</li>
 * </ul>
 *
 * <p>The state machine refuses {@link #DRAFT} → {@link #APPLIED}; every change
 * MUST pass through {@link #PROPOSED} and {@link #APPROVED}. This is what makes
 * the 4-eyes invariant procedural as well as DB-enforced.
 */
public enum ChangeRequestState {
    /** Maker is still editing the proposal — wizard server-side persistence (ADR-012). */
    DRAFT,
    /** Maker has submitted; awaiting checker review. */
    PROPOSED,
    /** Checker approved; ready to apply to the aggregate. */
    APPROVED,
    /** Approved change has been applied to the underlying aggregate. Terminal success. */
    APPLIED,
    /** Maker / checker rejected the change. Terminal failure; carries a reason. */
    REJECTED
}
