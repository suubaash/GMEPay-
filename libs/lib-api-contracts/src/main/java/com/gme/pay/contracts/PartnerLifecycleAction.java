package com.gme.pay.contracts;

/**
 * The four operator-driven lifecycle verbs exposed by config-registry's
 * {@code POST /v1/admin/partners/{partnerCode}/lifecycle/*} endpoints
 * (Slice 8 — see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 8" and ADR-011).
 *
 * <p>Every one of these actions is 4-eyes gated (ADR-008): the first operator's
 * call creates a {@code change_request} in PROPOSED, a second operator's call
 * approves + applies it. The action name is carried inside the change_request's
 * {@code payload_jsonb} so the lifecycle applier knows which transition to drive.
 *
 * <p>Mapping onto the {@link PartnerStatusTransitionTable} edges:
 * <ul>
 *   <li>{@link #ACTIVATE} — {@code UAT → LIVE}; the activation pre-condition
 *       gate must pass at approval time.</li>
 *   <li>{@link #SUSPEND} — {@code LIVE → SUSPENDED}; a {@link SuspensionReason}
 *       is required.</li>
 *   <li>{@link #REACTIVATE} — {@code SUSPENDED → LIVE}; clears the suspension
 *       fields.</li>
 *   <li>{@link #TERMINATE} — {@code LIVE|SUSPENDED → TERMINATED}; a free-text
 *       termination reason is required. Non-reversible.</li>
 * </ul>
 */
public enum PartnerLifecycleAction {
    ACTIVATE,
    SUSPEND,
    REACTIVATE,
    TERMINATE
}
