package com.gme.pay.txn.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *
 * <p>Per MONEY_CONVENTION.md the aggregate carries three immutable rate-lock
 * fields populated at commit-time (currently nullable for in-flight transactions):
 * <ul>
 *   <li>{@code bookedSettlementAmount} – partner-facing settlement liability under that partner's rounding rule</li>
 *   <li>{@code settlementRoundingMode} – the {@link RoundingMode} actually applied</li>
 *   <li>{@code roundingResidual}       – {@code precise - booked}, fed to revenue-ledger as rounding gain/loss</li>
 * </ul>
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

    // Rate-lock fields, populated at commit (Phase 1: nullable until APPROVED).
    private BigDecimal bookedSettlementAmount;
    private RoundingMode settlementRoundingMode;
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

    /**
     * Public rehydration constructor used by the persistence adapter to restore the
     * full aggregate – including rate-lock fields – from a stored row.
     * Kept public (not package-private) so adapters under
     * {@code com.gme.pay.txn.persistence} may reconstruct without reflection.
     */
    public Transaction(
            String txnRef,
            String partnerRef,
            BigDecimal sendAmount,
            String sendCcy,
            BigDecimal targetPayout,
            String targetCcy,
            TransactionStatus status,
            Instant createdAt,
            Instant updatedAt,
            BigDecimal bookedSettlementAmount,
            RoundingMode settlementRoundingMode,
            BigDecimal roundingResidual) {
        this.txnRef = txnRef;
        this.partnerRef = partnerRef;
        this.sendAmount = sendAmount;
        this.sendCcy = sendCcy;
        this.targetPayout = targetPayout;
        this.targetCcy = targetCcy;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.bookedSettlementAmount = bookedSettlementAmount;
        this.settlementRoundingMode = settlementRoundingMode;
        this.roundingResidual = roundingResidual;
    }

    // --- mutators (package-visible; only the state machine touches these) ---

    /** Called exclusively by {@link com.gme.pay.txn.domain.statemachine.TransactionStateMachine}. */
    public void applyStatus(TransactionStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Records the per-partner rate-lock at commit-time (see MONEY_CONVENTION.md).
     * Subsequent calls overwrite – immutability post-APPROVED is enforced by the
     * state machine, not by the aggregate.
     */
    public void applyRoundingLock(BigDecimal bookedSettlementAmount,
                                  RoundingMode settlementRoundingMode,
                                  BigDecimal roundingResidual) {
        this.bookedSettlementAmount = bookedSettlementAmount;
        this.settlementRoundingMode = settlementRoundingMode;
        this.roundingResidual = roundingResidual;
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

    public BigDecimal bookedSettlementAmount()     { return bookedSettlementAmount; }
    public RoundingMode settlementRoundingMode()   { return settlementRoundingMode; }
    public BigDecimal roundingResidual()           { return roundingResidual; }
}
