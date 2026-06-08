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
    // Rate-locked settlement booking (set once at commit; see MONEY_CONVENTION.md)
    private BigDecimal bookedSettlementAmount;
    private String settlementRoundingMode;
    private BigDecimal roundingResidual;

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

    /**
     * Locks the partner settlement booking at commit. The booked amount is the liability recorded
     * with the partner (rounded under the partner's rule); the residual is posted to REVENUE_ROUNDING.
     * Immutable once set.
     */
    public void lockSettlementBooking(BigDecimal booked, String roundingMode, BigDecimal residual) {
        if (this.bookedSettlementAmount != null) {
            throw new IllegalStateException("settlement booking already locked for " + txnRef);
        }
        this.bookedSettlementAmount = Objects.requireNonNull(booked, "booked");
        this.settlementRoundingMode = Objects.requireNonNull(roundingMode, "roundingMode");
        this.roundingResidual = Objects.requireNonNull(residual, "residual");
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
    public BigDecimal bookedSettlementAmount() { return bookedSettlementAmount; }
    public String settlementRoundingMode()     { return settlementRoundingMode; }
    public BigDecimal roundingResidual()        { return roundingResidual; }
}
