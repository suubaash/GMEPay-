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
 *
 * <p>Phase-4 (V003) enrichment adds the payment-executor 11-field create contract and
 * the 8-field status-patch lock fields.
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

    // V003: payment-executor 11-field create contract (nullable on old rows).
    private final Long partnerId;
    private final String partnerTxnRef;
    private final String schemeId;
    private final String direction;
    private final String paymentMode;
    private final String payoutCurrency;
    private final BigDecimal collectionAmount;
    private final String collectionCurrency;
    private final String merchantId;
    private final String quoteId;
    private final String paymentId;

    // V003: status-patch lock fields (nullable until PATCH applied).
    private String schemeTxnRef;
    private String schemeApprovalCode;
    private BigDecimal prefundDeductedUsd;
    private Instant approvedAt;

    /**
     * Creates a new transaction in {@link TransactionStatus#CREATED} state
     * using the legacy 5-field signature (kept for backward compat with unit tests).
     */
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
        // V003 fields null for legacy path
        this.partnerId = null;
        this.partnerTxnRef = null;
        this.schemeId = null;
        this.direction = null;
        this.paymentMode = null;
        this.payoutCurrency = null;
        this.collectionAmount = null;
        this.collectionCurrency = null;
        this.merchantId = null;
        this.quoteId = null;
        this.paymentId = null;
    }

    /**
     * Creates a new transaction using the full payment-executor 11-field contract.
     * partnerRef is derived from partnerTxnRef for backward compat.
     */
    public Transaction(
            Long partnerId,
            String partnerTxnRef,
            String schemeId,
            String direction,
            String paymentMode,
            BigDecimal targetPayout,
            String payoutCurrency,
            BigDecimal collectionAmount,
            String collectionCurrency,
            String merchantId,
            String quoteId) {
        this.txnRef = UUID.randomUUID().toString();
        this.paymentId = UUID.randomUUID().toString();
        this.partnerId = Objects.requireNonNull(partnerId, "partnerId");
        this.partnerTxnRef = Objects.requireNonNull(partnerTxnRef, "partnerTxnRef");
        this.schemeId = schemeId;
        this.direction = direction;
        this.paymentMode = paymentMode;
        this.targetPayout = Objects.requireNonNull(targetPayout, "targetPayout");
        this.payoutCurrency = Objects.requireNonNull(payoutCurrency, "payoutCurrency");
        this.collectionAmount = Objects.requireNonNull(collectionAmount, "collectionAmount");
        this.collectionCurrency = Objects.requireNonNull(collectionCurrency, "collectionCurrency");
        this.merchantId = merchantId;
        this.quoteId = quoteId;
        // Legacy fields mapped from V003 inputs
        this.partnerRef = partnerTxnRef;
        this.sendAmount = collectionAmount;
        this.sendCcy = collectionCurrency;
        this.targetCcy = payoutCurrency;
        this.status = TransactionStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Package-private constructor used by the persistence adapter when rehydrating from DB.
     * Accepts the three rate-lock fields (nullable for in-flight rows).
     */
    Transaction(
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
            String settlementRoundingMode,
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
        this.settlementRoundingMode = settlementRoundingMode == null
                ? null
                : RoundingMode.valueOf(settlementRoundingMode);
        this.roundingResidual = roundingResidual;
        // V003 fields absent in legacy rows
        this.partnerId = null;
        this.partnerTxnRef = null;
        this.schemeId = null;
        this.direction = null;
        this.paymentMode = null;
        this.payoutCurrency = null;
        this.collectionAmount = null;
        this.collectionCurrency = null;
        this.merchantId = null;
        this.quoteId = null;
        this.paymentId = null;
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
        // V003 fields absent in legacy rows
        this.partnerId = null;
        this.partnerTxnRef = null;
        this.schemeId = null;
        this.direction = null;
        this.paymentMode = null;
        this.payoutCurrency = null;
        this.collectionAmount = null;
        this.collectionCurrency = null;
        this.merchantId = null;
        this.quoteId = null;
        this.paymentId = null;
    }

    /**
     * Full rehydration constructor (V003+): restores all fields including Phase-4 enrichment
     * columns and status-patch lock fields.
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
            BigDecimal roundingResidual,
            Long partnerId,
            String partnerTxnRef,
            String schemeId,
            String direction,
            String paymentMode,
            String payoutCurrency,
            BigDecimal collectionAmount,
            String collectionCurrency,
            String merchantId,
            String quoteId,
            String paymentId,
            String schemeTxnRef,
            String schemeApprovalCode,
            BigDecimal prefundDeductedUsd,
            Instant approvedAt) {
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
        this.partnerId = partnerId;
        this.partnerTxnRef = partnerTxnRef;
        this.schemeId = schemeId;
        this.direction = direction;
        this.paymentMode = paymentMode;
        this.payoutCurrency = payoutCurrency;
        this.collectionAmount = collectionAmount;
        this.collectionCurrency = collectionCurrency;
        this.merchantId = merchantId;
        this.quoteId = quoteId;
        this.paymentId = paymentId;
        this.schemeTxnRef = schemeTxnRef;
        this.schemeApprovalCode = schemeApprovalCode;
        this.prefundDeductedUsd = prefundDeductedUsd;
        this.approvedAt = approvedAt;
    }

    // --- mutators (only the state machine and service touch these) ---

    /** Called exclusively by {@link com.gme.pay.txn.domain.statemachine.TransactionStateMachine}. */
    public void applyStatus(TransactionStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Records the per-partner rate-lock at commit-time (see MONEY_CONVENTION.md).
     * Subsequent calls overwrite – immutability post-APPROVED is enforced by the
     * state machine, not by the aggregate.
     *
     * <p>Prefer {@link #lockSettlementBooking(BigDecimal, String, BigDecimal)} for new code:
     * it enforces single-shot locking and accepts the rounding mode as a string so the
     * commit-path caller does not depend on {@link RoundingMode}.
     */
    public void applyRoundingLock(BigDecimal bookedSettlementAmount,
                                  RoundingMode settlementRoundingMode,
                                  BigDecimal roundingResidual) {
        this.bookedSettlementAmount = bookedSettlementAmount;
        this.settlementRoundingMode = settlementRoundingMode;
        this.roundingResidual = roundingResidual;
        this.updatedAt = Instant.now();
    }

    /**
     * One-shot lock of the per-partner settlement-booking fields at commit (MONEY_CONVENTION.md).
     * All three arguments are required; the rounding mode is supplied as the enum's
     * {@code name()} (e.g. {@code "DOWN"}, {@code "HALF_UP"}) so callers do not depend on
     * {@link RoundingMode}.
     *
     * <p>Once {@code booked} has been set, subsequent calls fail with
     * {@link IllegalStateException} – the booked liability is immutable once persisted.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code roundingMode} is not a known {@link RoundingMode}
     * @throws IllegalStateException if {@code bookedSettlementAmount} is already populated
     */
    public void lockSettlementBooking(BigDecimal booked, String roundingMode, BigDecimal residual) {
        Objects.requireNonNull(booked, "booked");
        Objects.requireNonNull(roundingMode, "roundingMode");
        Objects.requireNonNull(residual, "residual");
        if (this.bookedSettlementAmount != null) {
            throw new IllegalStateException(
                    "settlement booking already locked for txn " + txnRef
                            + " (booked=" + this.bookedSettlementAmount + ")");
        }
        this.bookedSettlementAmount = booked;
        this.settlementRoundingMode = RoundingMode.valueOf(roundingMode);
        this.roundingResidual = residual;
        this.updatedAt = Instant.now();
    }

    /**
     * Applies the status-patch lock fields from the PATCH /v1/transactions/{ref}/status
     * endpoint. These fields are set once when the payment-executor commits the scheme result.
     */
    public void applyStatusPatch(String schemeTxnRef, String schemeApprovalCode,
                                 BigDecimal prefundDeductedUsd, Instant approvedAt,
                                 BigDecimal bookedSettlementAmount, String settlementRoundingMode,
                                 BigDecimal roundingResidual) {
        this.schemeTxnRef = schemeTxnRef;
        this.schemeApprovalCode = schemeApprovalCode;
        this.prefundDeductedUsd = prefundDeductedUsd;
        this.approvedAt = approvedAt;
        if (bookedSettlementAmount != null && settlementRoundingMode != null && roundingResidual != null) {
            this.bookedSettlementAmount = bookedSettlementAmount;
            this.settlementRoundingMode = RoundingMode.valueOf(settlementRoundingMode);
            this.roundingResidual = roundingResidual;
        }
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

    // V003 accessors
    public Long partnerId()              { return partnerId; }
    public String partnerTxnRef()        { return partnerTxnRef; }
    public String schemeId()             { return schemeId; }
    public String direction()            { return direction; }
    public String paymentMode()          { return paymentMode; }
    public String payoutCurrency()       { return payoutCurrency; }
    public BigDecimal collectionAmount() { return collectionAmount; }
    public String collectionCurrency()   { return collectionCurrency; }
    public String merchantId()           { return merchantId; }
    public String quoteId()              { return quoteId; }
    public String paymentId()            { return paymentId; }
    public String schemeTxnRef()         { return schemeTxnRef; }
    public String schemeApprovalCode()   { return schemeApprovalCode; }
    public BigDecimal prefundDeductedUsd() { return prefundDeductedUsd; }
    public Instant approvedAt()          { return approvedAt; }
}
