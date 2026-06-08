package com.gme.pay.txn.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * In-process domain aggregate representing a GMEPay+ payment transaction.
 *
 * <p>This is a plain-Java aggregate – NOT a JPA entity – keeping the domain
 * model free from persistence concerns for unit-testability.  A persistence
 * adapter (repository) maps between this object and the {@code transaction}
 * table in the {@code txn} PostgreSQL database.
 */
public class Transaction {

    private final String txnRef;
    private final String partnerRef;
    private final BigDecimal sendAmount;
    private final String sendCcy;
    private final BigDecimal targetPayout;
    private final String targetCcy;
    private TransactionStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    /** Creates a new transaction in {@link TransactionStatus#CREATED} state. */
    public Transaction(
            String partnerRef,
            BigDecimal sendAmount,
            String sendCcy,
            BigDecimal targetPayout,
            String targetCcy) {
        this.txnRef = UUID.randomUUID().toString();
        this.partnerRef = Objects.requireNonNull(partnerRef, "partnerRef");
        this.sendAmount = Objects.requireNonNull(sendAmount, "sendAmount");
        this.sendCcy = Objects.requireNonNull(sendCcy, "sendCcy");
        this.targetPayout = Objects.requireNonNull(targetPayout, "targetPayout");
        this.targetCcy = Objects.requireNonNull(targetCcy, "targetCcy");
        this.status = TransactionStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Package-private constructor used by the persistence adapter when rehydrating from DB. */
    Transaction(
            String txnRef,
            String partnerRef,
            BigDecimal sendAmount,
            String sendCcy,
            BigDecimal targetPayout,
            String targetCcy,
            TransactionStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.txnRef = txnRef;
        this.partnerRef = partnerRef;
        this.sendAmount = sendAmount;
        this.sendCcy = sendCcy;
        this.targetPayout = targetPayout;
        this.targetCcy = targetCcy;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // --- mutators (package-visible; only the state machine touches these) ---

    /** Called exclusively by {@link com.gme.pay.txn.domain.statemachine.TransactionStateMachine}. */
    public void applyStatus(TransactionStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    // --- accessors ---

    public String txnRef()       { return txnRef; }
    public String partnerRef()   { return partnerRef; }
    public BigDecimal sendAmount()    { return sendAmount; }
    public String sendCcy()      { return sendCcy; }
    public BigDecimal targetPayout()  { return targetPayout; }
    public String targetCcy()    { return targetCcy; }
    public TransactionStatus status() { return status; }
    public Instant createdAt()   { return createdAt; }
    public Instant updatedAt()   { return updatedAt; }
}
