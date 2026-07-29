package com.gme.pay.scheme.ninepay.status;

/**
 * Canonical lifecycle of a 9Pay payout as tracked in {@code np_payouts.status}.
 *
 * <p>9Pay's own machine is {@code PENDING → PROCESSING → SUCCESS | FAIL} where only
 * SUCCESS/FAIL are "final" — but IPN message code 009 (bank reversal) can flip a SUCCESS
 * after the fact, and code 008 parks a transfer pending merchant confirmation. This enum
 * therefore adds {@link #REVERSED}, {@link #HELD}, plus the adapter-side states
 * {@link #SUBMITTED} (accepted locally, scheme verdict not yet stored) and
 * {@link #UNKNOWN} (ambiguous timeout, unresolved by polling — never auto-failed,
 * ADR-016 anti-double-payout).</p>
 */
public enum PayoutStatus {

    /** Persisted locally; no scheme verdict recorded yet. */
    SUBMITTED,
    /** Accepted by 9Pay, not yet sent to the bank. */
    PENDING,
    /** At the bank. */
    PROCESSING,
    /** Paid out (can still be reversed by IPN code 009). */
    SUCCESS,
    /** Definitively failed/rejected. */
    FAILED,
    /** Held by 9Pay pending merchant confirmation (IPN code 008) — needs an ops decision. */
    HELD,
    /** Bank reversed the payment AFTER success (IPN code 009). */
    REVERSED,
    /** Ambiguous (timeout both on submit and on the lookup poll). Resolve by later polling. */
    UNKNOWN;

    /** True when no further scheme transition is expected (009 can still flip SUCCESS). */
    public boolean isFinal() {
        return this == SUCCESS || this == FAILED || this == REVERSED;
    }
}
