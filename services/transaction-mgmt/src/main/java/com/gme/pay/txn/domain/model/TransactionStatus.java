package com.gme.pay.txn.domain.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * State-machine statuses for a GMEPay+ transaction.
 *
 * <p>Wave scope: CREATED → PENDING_DEBIT → APPROVED / FAILED / CANCELLED
 *
 * <p>Full set from SAD-02 §5.2:
 * <ul>
 *   <li>CREATED       – transaction record initialised, rate quote linked</li>
 *   <li>PENDING_DEBIT – CommitTransaction received; prefunding deduction in progress (OVERSEAS)</li>
 *   <li>APPROVED      – scheme confirmed success (terminal)</li>
 *   <li>FAILED        – terminal failure (scheme reject, TTL, insufficient prefunding)</li>
 *   <li>CANCELLED     – same-day cancel after APPROVED (terminal)</li>
 * </ul>
 */
public enum TransactionStatus {

    /** Transaction record initialised; rate quote linked. Not yet committed. */
    CREATED,

    /** CommitTransaction received; prefunding deduction in progress (OVERSEAS path). */
    PENDING_DEBIT,

    /** Scheme confirmed successful payment. Terminal. */
    APPROVED,

    /** Terminal failure – scheme reject, TTL expiry, or insufficient prefunding. */
    FAILED,

    /** Same-day cancel requested after APPROVED. Terminal. */
    CANCELLED;

    /** Returns {@code true} for states from which no further transition is allowed. */
    public boolean isTerminal() {
        return this == APPROVED || this == FAILED || this == CANCELLED;
    }

    /** Serialises as the enum constant name (e.g. {@code "APPROVED"}). */
    @JsonValue
    public String jsonValue() {
        return name();
    }
}
