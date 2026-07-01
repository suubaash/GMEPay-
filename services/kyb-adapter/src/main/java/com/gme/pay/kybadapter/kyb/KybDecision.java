package com.gme.pay.kybadapter.kyb;

/**
 * The collapsed verdict of a full KYB verification run — sanctions/PEP
 * screening, business-registration verification and document completeness
 * folded into one operator-facing decision.
 *
 * <p>This is evidence-driven guidance, NOT an activation authorisation: per
 * lib-kyb's {@code ScreeningResult} contract, "can this partner transact" stays
 * an ADR-008 4-eyes operator decision. {@link #MANUAL_REVIEW} explicitly routes
 * the run to that human queue rather than auto-deciding.
 */
public enum KybDecision {
    /** No sanctions hits, business registration verified, documents complete. */
    PASS,
    /** A confirmed sanctions/watchlist HIT or an absent/contradicted business registration. */
    FAIL,
    /** Fuzzy screening matches, a name/number mismatch, or missing documents — route to an analyst. */
    MANUAL_REVIEW
}
