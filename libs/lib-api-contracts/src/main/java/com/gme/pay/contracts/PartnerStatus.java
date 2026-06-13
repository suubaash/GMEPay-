package com.gme.pay.contracts;

/**
 * Lifecycle state of a partner aggregate.
 *
 * <p>The enum lists every state in the full Partner FSM described in
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 8 — Credentials + Lifecycle + Reporting"
 * and ADR-011, even though Slice 1 only writes the head of the chain
 * ({@link #ONBOARDING}). Defining the full enum up-front avoids a churny
 * Expand/Contract migration each time a later slice surfaces another transition,
 * and lets the {@code change_request} state machine declare its guards against
 * a stable type from day one.
 *
 * <p>Forward-flow:
 * <pre>
 *   DRAFT → ONBOARDING → KYB_PENDING → KYB_APPROVED → CONTRACT_SIGNED
 *         → SANDBOX → UAT → LIVE
 * </pre>
 *
 * <p>Terminal-ish states reachable from {@link #LIVE}:
 * <ul>
 *   <li>{@link #SUSPENDED} — recoverable; an operator can re-propose LIVE.</li>
 *   <li>{@link #TERMINATED} — non-recoverable; the partner is closed.</li>
 * </ul>
 *
 * <p>The single source of truth for which edges are legal (and which require
 * 4-eyes approval) is {@link PartnerStatusTransitionTable} — keep the two in
 * lock-step with the V025 {@code ck_partners_status} CHECK roster.
 */
public enum PartnerStatus {
    /**
     * Operator has opened a brand-new draft shell (Slice 8 widened the FSM head).
     * No wizard step has been saved yet. Slice 1's draft endpoints historically
     * minted rows straight into {@link #ONBOARDING}; DRAFT exists so the full
     * lifecycle documented in ADR-011 has its true head state and the
     * {@code DRAFT → ONBOARDING} edge can be driven explicitly.
     */
    DRAFT,
    /** Operator has opened a draft (Slice 1). No data yet committed. */
    ONBOARDING,
    /** KYB documents collected; awaiting compliance review (Slice 3). */
    KYB_PENDING,
    /** Compliance has approved KYB; partner can proceed to contract (Slice 3). */
    KYB_APPROVED,
    /** Contract is signed and effective dates set (Slice 6). */
    CONTRACT_SIGNED,
    /** Sandbox credentials issued; partner integrates against the test endpoint (Slice 8). */
    SANDBOX,
    /** UAT verification ongoing (Slice 8). */
    UAT,
    /** Partner is processing real transactions (Slice 8). */
    LIVE,
    /** Operations halted by operator or auto-rule; reversible (Slice 5/8). */
    SUSPENDED,
    /** Partner closed; non-reversible (Slice 8). */
    TERMINATED
}
