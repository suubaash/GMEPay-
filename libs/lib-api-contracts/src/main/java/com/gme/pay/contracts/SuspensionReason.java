package com.gme.pay.contracts;

/**
 * Machine-readable reason codes for {@code LIVE → SUSPENDED} transitions
 * (Slice 8). Mirrors the V025 {@code ck_partners_suspension_reason} CHECK
 * roster on {@code partners.suspension_reason} — keep the two in lock-step:
 * adding a value here without widening the CHECK fails at INSERT, and vice
 * versa leaves a dead enum value.
 *
 * <p>The code answers "WHY is this partner suspended" for dashboards, alerting
 * and the regulator narrative; the free-text {@code suspension_notes} column
 * (≤500 chars) carries the operator's specifics.
 */
public enum SuspensionReason {

    /** A configured exposure/velocity limit was breached (Slice 5 auto-suspend feeds this). */
    LIMIT_BREACH,

    /** Sanctions screening produced a hit that compliance has not cleared. */
    SANCTIONS_HIT,

    /** API key / HMAC secret / webhook signing secret believed compromised. */
    CREDENTIAL_COMPROMISE,

    /** KYB review lapsed (next_review_date passed without re-approval). */
    KYB_LAPSED,

    /** The commercial contract's effective window ended without renewal. */
    CONTRACT_EXPIRED,

    /** Manual operator decision not covered by a more specific code. */
    OPERATOR_INITIATED
}
