package com.gme.pay.txn.domain.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * State-machine statuses for a GMEPay+ transaction.
 *
 * <p>Lifecycle (SAD-02 §5.2):
 * CREATED → PENDING_DEBIT → DEBITED → SCHEME_SENT → APPROVED / UNCERTAIN / FAILED,
 * with the post-APPROVED branches REVERSED / REFUNDED and the pre-debit CANCELLED.
 *
 * <p>Status meanings:
 * <ul>
 *   <li>CREATED       – transaction record initialised, rate quote linked</li>
 *   <li>PENDING_DEBIT – CommitTransaction received; prefunding deduction in progress (OVERSEAS)</li>
 *   <li>SCHEME_SENT   – scheme adapter call dispatched; awaiting scheme response</li>
 *   <li>APPROVED      – scheme confirmed success (terminal for the sweeper)</li>
 *   <li>UNCERTAIN     – no scheme response within SLA; awaits batch reconciliation</li>
 *   <li>FAILED        – terminal failure (scheme reject, TTL, insufficient prefunding)</li>
 *   <li>CANCELLED     – same-day cancel after APPROVED (terminal)</li>
 *   <li>REVERSED      – same-day cancel that reached the scheme + reversed prefunding (terminal)</li>
 *   <li>REFUNDED      – explicit post-settlement refund (terminal)</li>
 * </ul>
 */
public enum TransactionStatus {

    /** Transaction record initialised; rate quote linked. Not yet committed. */
    CREATED,

    /** CommitTransaction received; prefunding deduction in progress (OVERSEAS path). */
    PENDING_DEBIT,

    /**
     * Scheme adapter call dispatched; awaiting the scheme's synchronous response.
     * Set <em>before</em> the adapter HTTP call so a mid-call crash leaves a row the
     * reconciliation engine can pick up (SAD-02 §5.2). Non-terminal.
     */
    SCHEME_SENT,

    /** Scheme confirmed successful payment. Terminal. */
    APPROVED,

    /**
     * No scheme response within SLA (timeout). The prefunding deduction is <em>held</em>
     * (not reversed) until batch reconciliation resolves the transaction to APPROVED or
     * FAILED (SAD-02 §5.2). Non-terminal — exits via reconciliation only.
     */
    UNCERTAIN,

    /** Terminal failure – scheme reject, TTL expiry, or insufficient prefunding. */
    FAILED,

    /** Same-day cancel requested after APPROVED. Terminal. */
    CANCELLED,

    /** Reversed after APPROVED (same-day cancel that reached the scheme + reversed prefunding). Terminal. */
    REVERSED,

    /** Explicit refund issued after APPROVED. Terminal. */
    REFUNDED;

    /** Returns {@code true} for states from which no further transition is allowed. */
    public boolean isTerminal() {
        return this == APPROVED || this == FAILED || this == CANCELLED
                || this == REVERSED || this == REFUNDED;
    }

    /** Serialises as the enum constant name (e.g. {@code "APPROVED"}). */
    @JsonValue
    public String jsonValue() {
        return name();
    }
}
