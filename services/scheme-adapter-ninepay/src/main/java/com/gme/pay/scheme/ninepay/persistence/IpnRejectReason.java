package com.gme.pay.scheme.ninepay.persistence;

/**
 * Why an inbound 9Pay IPN was audited but NOT applied to the payout — gap <b>T5-4</b>.
 *
 * <p>Persisted as {@code np_ipn_events.reject_reason} (V002). Every value here is a
 * <b>silently-ignored-but-recorded</b> outcome: 9Pay still gets a 2xx ACK (except
 * {@link #SIGNATURE_INVALID}, which is a 400 so 9Pay redelivers), because the correct
 * answer to a replay is "I already have this", not an error that triggers more retries.
 */
public enum IpnRejectReason {

    /**
     * Same 9Pay event identity ({@code request_id|trans_id|code}) as an already-applied
     * event — a resend or a replay. Ignored idempotently; in particular a replayed code
     * 009 cannot reverse a payout twice.
     */
    DUPLICATE,

    /**
     * The signed {@code created_at} is older than the newest event already applied to this
     * payout: an out-of-order or deliberately-delayed delivery. Blocks the "replay the old
     * 000 after the 009" attack. Equal timestamps are allowed — 9Pay's genuine 009 carries
     * the SAME {@code created_at} as the 000 it reverses.
     */
    STALE_ORDER,

    /**
     * Applying it would move the payout BACKWARDS through its lifecycle (e.g. a late
     * PENDING landing on a SUCCESS, or anything at all on a REVERSED). {@code SUCCESS ->
     * REVERSED} is deliberately NOT a regression — that is the genuine bank-reversal path.
     */
    STATUS_REGRESSION,

    /** Signed {@code created_at} beyond the configured acceptance window. */
    EXPIRED,

    /** RSA signature did not verify against 9Pay's public key (or no key is configured). */
    SIGNATURE_INVALID,

    /** No local payout carries this {@code request_id} — audited for ops, nothing to update. */
    UNKNOWN_REQUEST_ID
}
