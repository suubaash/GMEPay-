package com.gme.pay.settlement.batch;

/** Thrown on a disallowed {@link SettlementBatchStatus} transition. */
public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(String message) {
        super(message);
    }
}
