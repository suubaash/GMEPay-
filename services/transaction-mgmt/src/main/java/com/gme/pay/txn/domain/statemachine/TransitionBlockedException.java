package com.gme.pay.txn.domain.statemachine;

import com.gme.pay.txn.domain.model.TransactionStatus;

/**
 * Thrown when an attempt is made to transition a transaction to a state not permitted by
 * {@link TransactionTransitions}.  This indicates either a programming bug or a race condition
 * where state has advanced beyond what was expected.
 */
public class TransitionBlockedException extends RuntimeException {

    private final TransactionStatus from;
    private final TransactionStatus to;
    private final String txnRef;

    public TransitionBlockedException(String txnRef, TransactionStatus from, TransactionStatus to) {
        super("Transition blocked for txn %s: %s → %s is not a permitted pair"
                .formatted(txnRef, from, to));
        this.txnRef = txnRef;
        this.from = from;
        this.to = to;
    }

    public TransitionBlockedException(
            String txnRef, TransactionStatus from, TransactionStatus to, Throwable cause) {
        super("Transition blocked for txn %s: %s → %s is not a permitted pair"
                .formatted(txnRef, from, to), cause);
        this.txnRef = txnRef;
        this.from = from;
        this.to = to;
    }

    public TransactionStatus getFrom()  { return from; }
    public TransactionStatus getTo()    { return to; }
    public String getTxnRef()           { return txnRef; }
}
