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

    // OI-01: reason code set when a transaction enters FAILED (e.g. "APPROVAL_TIMEOUT").
    private String failureReason;

    // V005: gross merchant fee rate snapshot (config-registry merchant_fee_schedule, V032),
    // captured at creation — the rate that applied then. Nullable on legacy / pre-resolution rows.
    private BigDecimal merchantFeeRate;

    // V007: committed-FX projection — rate-locked fields captured best-effort at commit
    // (APPROVED). Drives GET /v1/transactions/fx-committed and the transaction.committed event.
    // All nullable: a same-currency txn leaves the rate fields null; a legacy / pre-wiring txn
    // leaves the whole block null. NEVER set on the create path.
    private BigDecimal offerRateColl;
    private BigDecimal crossRate;
    private BigDecimal collectionMarginUsd;
    private BigDecimal payoutMarginUsd;
    private BigDecimal usdAmount;
    private Boolean sameCcyShortcircuit;
    private java.time.LocalDate settlementDate;
    private Instant committedAt;

    // V007: refund enrichment — populated when a refund is recorded against the txn.
    private BigDecimal refundAmountKrw;
    private String qrCodeId;
    private Instant refundedAt;
    private String originalPaymentTxnRef;

    // Wave-3 (V008): rate-lock pool fields carried on the create/commit contract so the
    // committed-FX projection can derive a MARGIN-ACCURATE offerRateColl instead of the
    // zero-margin approximation. All nullable (legacy rows + same-ccy legs leave them null).
    //   collectionUsd = the real collection-leg USD amount (send_usd_cost base / FX1015 #14
    //                   denominator), authoritative over the prefundDeductedUsd proxy.
    //   costRateColl / costRatePay = per-leg cost rates (market±buffer).
    //   payoutUsdCost = payout-leg USD cost.
    private BigDecimal collectionUsd;
    private BigDecimal costRateColl;
    private BigDecimal costRatePay;
    private BigDecimal payoutUsdCost;

    // V009 (Ops): operator force-resolution audit — set when an operator force-resolves an
    // UNCERTAIN transaction to a terminal state (COMPLETED→APPROVED / REVERSED). Records WHY and
    // WHO for the transaction history/audit. All nullable (never set on the normal lifecycle).
    private String resolutionReason;
    private String resolvedBy;
    private Instant resolvedAt;

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
        this(txnRef, partnerRef, sendAmount, sendCcy, targetPayout, targetCcy,
                status, createdAt, updatedAt,
                bookedSettlementAmount, settlementRoundingMode, roundingResidual,
                partnerId, partnerTxnRef, schemeId, direction, paymentMode,
                payoutCurrency, collectionAmount, collectionCurrency,
                merchantId, quoteId, paymentId,
                schemeTxnRef, schemeApprovalCode, prefundDeductedUsd, approvedAt,
                null /* failureReason */);
    }

    /**
     * Full rehydration constructor (V004+): restores all fields including OI-01 failureReason.
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
            Instant approvedAt,
            String failureReason) {
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
        this.failureReason = failureReason;
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
     * Records the reason code for a FAILED terminal transition (OI-01).
     * Idempotent — a second call overwrites, but the FSM prevents re-entering FAILED.
     */
    public void applyFailureReason(String reason) {
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * Snapshots the gross merchant fee rate resolved at creation (V005). Set on the create
     * path and on rehydration from the DB. The rate is immutable for the life of the txn (the
     * rate that applied at creation), so settlement always reads a stable value. Does NOT bump
     * {@code updatedAt} — it is a creation-time snapshot, also replayed during rehydration.
     */
    public void applyMerchantFeeRate(BigDecimal merchantFeeRate) {
        this.merchantFeeRate = merchantFeeRate;
    }

    /**
     * Records the rate-lock pool fields (Wave-3 / V008) — margins, the real collection-leg USD
     * amount and per-leg cost rates — carried on the create or commit contract. These feed the
     * margin-accurate {@code offerRateColl} derivation at commit. All nullable; null arguments
     * leave the corresponding field empty (the projection then falls back to its proxies). Also
     * replayed during rehydration, so it does NOT bump {@code updatedAt}.
     */
    public void applyRateLockPool(BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd,
                                  BigDecimal collectionUsd, BigDecimal costRateColl,
                                  BigDecimal costRatePay, BigDecimal payoutUsdCost) {
        if (collectionMarginUsd != null) this.collectionMarginUsd = collectionMarginUsd;
        if (payoutMarginUsd != null)     this.payoutMarginUsd = payoutMarginUsd;
        if (collectionUsd != null)       this.collectionUsd = collectionUsd;
        if (costRateColl != null)        this.costRateColl = costRateColl;
        if (costRatePay != null)         this.costRatePay = costRatePay;
        if (payoutUsdCost != null)       this.payoutUsdCost = payoutUsdCost;
    }

    /**
     * Captures the committed-FX projection fields at commit-time (V007). Called from the commit
     * path (APPROVED) and replayed during rehydration; all arguments are nullable so a
     * same-currency short-circuit or a pre-wiring txn can be persisted with partial data. Does
     * NOT bump {@code updatedAt} — it is a commit-time snapshot, also replayed on rehydration.
     */
    public void applyCommittedFx(BigDecimal offerRateColl, BigDecimal crossRate,
                                 BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd,
                                 BigDecimal usdAmount, Boolean sameCcyShortcircuit,
                                 java.time.LocalDate settlementDate, Instant committedAt) {
        this.offerRateColl = offerRateColl;
        this.crossRate = crossRate;
        this.collectionMarginUsd = collectionMarginUsd;
        this.payoutMarginUsd = payoutMarginUsd;
        this.usdAmount = usdAmount;
        this.sameCcyShortcircuit = sameCcyShortcircuit;
        this.settlementDate = settlementDate;
        this.committedAt = committedAt;
    }

    /**
     * Derives and captures the committed-FX projection from data already on the aggregate, the
     * moment the txn commits (APPROVED). Best-effort: when the inputs needed for the FX rates are
     * absent (no collection amount, or a same-currency leg) the rate fields stay null but the
     * commit still records {@code usdAmount}/{@code committedAt}/{@code sameCcyShortcircuit}.
     *
     * <p>Per subash-fx (calculation-model.md), with {@code send_amount = collectionAmount} (the
     * service charge is already carved out upstream by payment-executor):
     * <ul>
     *   <li>{@code offerRateColl = send_amount / (collection_usd - collection_margin_usd)} (FX1015 #14)</li>
     *   <li>{@code crossRate     = target_payout / send_amount}</li>
     * </ul>
     *
     * <p>Wave-3: the USD base prefers the REAL {@code collectionUsd} persisted from the rate-lock
     * pool (carried on create/commit), falling back to the {@code prefundDeductedUsd} proxy only
     * when it is absent (older rows). Likewise the margins prefer the explicit arguments, falling
     * back to the persisted {@code collectionMarginUsd}/{@code payoutMarginUsd} on the aggregate;
     * when BOTH are absent the margin is ZERO and {@code offerRateColl} collapses to
     * {@code send_amount / collection_usd} (the zero-margin approximation kept for legacy rows).
     * This method never throws.
     *
     * @param collectionMarginUsd nullable; falls back to the persisted margin, then 0
     * @param payoutMarginUsd     nullable; falls back to the persisted margin, then 0
     */
    public void captureCommittedFxAtCommit(BigDecimal collectionMarginUsd,
                                           BigDecimal payoutMarginUsd,
                                           Instant committedAt) {
        boolean sameCcy = sendCcy != null && sendCcy.equals(targetCcy);
        // Prefer the real collection-leg USD amount; fall back to the deducted-USD proxy.
        BigDecimal usd = collectionUsd != null ? collectionUsd : prefundDeductedUsd;
        // Prefer the explicit argument, then the persisted margin on the aggregate, then 0.
        BigDecimal effColl = collectionMarginUsd != null ? collectionMarginUsd : this.collectionMarginUsd;
        BigDecimal effPay  = payoutMarginUsd != null ? payoutMarginUsd : this.payoutMarginUsd;

        BigDecimal offer = computeOfferRateColl(sendAmount, usd, effColl, sameCcy);
        BigDecimal cross = computeCrossRate(sendAmount, targetPayout, sameCcy);

        applyCommittedFx(offer, cross,
                effColl, effPay, usd,
                sameCcy, settlementDate, committedAt != null ? committedAt : Instant.now());
    }

    /**
     * FX1015 #14: {@code offerRateColl = send_amount / (collection_usd - collection_margin_usd)}.
     * Returns null for a same-currency short-circuit or when an input is missing / would divide
     * by a non-positive denominator (best-effort — the projection field is explicitly nullable).
     */
    public static BigDecimal computeOfferRateColl(BigDecimal sendAmount, BigDecimal collectionUsd,
                                                  BigDecimal collectionMarginUsd, boolean sameCcy) {
        if (sameCcy || sendAmount == null || collectionUsd == null) return null;
        BigDecimal margin = collectionMarginUsd != null ? collectionMarginUsd : BigDecimal.ZERO;
        BigDecimal denom = collectionUsd.subtract(margin);
        if (denom.signum() <= 0) return null;
        return sendAmount.divide(denom, 8, RoundingMode.HALF_UP);
    }

    /**
     * {@code crossRate = target_payout / send_amount}. Null for a same-currency short-circuit or
     * when an input is missing / send_amount is zero.
     */
    public static BigDecimal computeCrossRate(BigDecimal sendAmount, BigDecimal targetPayout,
                                              boolean sameCcy) {
        if (sameCcy || sendAmount == null || targetPayout == null || sendAmount.signum() == 0) {
            return null;
        }
        return targetPayout.divide(sendAmount, 8, RoundingMode.HALF_UP);
    }

    /**
     * Records the operator force-resolution audit (V009 / Ops) — the reason and operator id that
     * accompanied a manual resolution of an UNCERTAIN transaction. Set immediately before the FSM
     * transition to the terminal state so the history/audit reflects who forced the outcome and why.
     * Replayed on rehydration, so it does NOT bump {@code updatedAt} when {@code stamp} is false.
     */
    public void applyOperatorResolution(String resolutionReason, String resolvedBy,
                                        Instant resolvedAt, boolean stamp) {
        this.resolutionReason = resolutionReason;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        if (stamp) {
            this.updatedAt = Instant.now();
        }
    }

    /**
     * Records refund enrichment (V007) so the committed/refund projection carries the fields
     * scheme-adapter / settlement read. All nullable; does not change status (the FSM owns that).
     */
    public void applyRefundEnrichment(BigDecimal refundAmountKrw, String qrCodeId,
                                      Instant refundedAt, String originalPaymentTxnRef) {
        this.refundAmountKrw = refundAmountKrw;
        this.qrCodeId = qrCodeId;
        this.refundedAt = refundedAt;
        this.originalPaymentTxnRef = originalPaymentTxnRef;
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

    /**
     * Wave-3 overload: applies the status-patch lock fields PLUS the rate-lock pool margins/cost
     * rates carried on the commit, so {@code offerRateColl} can be derived margin-accurately. The
     * pool fields are merged via {@link #applyRateLockPool} (null-skip), so a commit that omits
     * them leaves any earlier create-time pool intact. The {@code collectionUsd} here is the real
     * collection-leg USD amount (preferred over the prefund proxy at commit).
     */
    public void applyStatusPatch(String schemeTxnRef, String schemeApprovalCode,
                                 BigDecimal prefundDeductedUsd, Instant approvedAt,
                                 BigDecimal bookedSettlementAmount, String settlementRoundingMode,
                                 BigDecimal roundingResidual,
                                 BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd,
                                 BigDecimal collectionUsd, BigDecimal costRateColl,
                                 BigDecimal costRatePay) {
        applyStatusPatch(schemeTxnRef, schemeApprovalCode, prefundDeductedUsd, approvedAt,
                bookedSettlementAmount, settlementRoundingMode, roundingResidual);
        applyRateLockPool(collectionMarginUsd, payoutMarginUsd, collectionUsd,
                costRateColl, costRatePay, null);
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
    public String failureReason()        { return failureReason; }
    public BigDecimal merchantFeeRate()  { return merchantFeeRate; }

    // V007 committed-FX accessors
    public BigDecimal offerRateColl()       { return offerRateColl; }
    public BigDecimal crossRate()           { return crossRate; }
    public BigDecimal collectionMarginUsd() { return collectionMarginUsd; }
    public BigDecimal payoutMarginUsd()     { return payoutMarginUsd; }
    public BigDecimal usdAmount()           { return usdAmount; }
    public Boolean sameCcyShortcircuit()    { return sameCcyShortcircuit; }
    public java.time.LocalDate settlementDate() { return settlementDate; }
    public Instant committedAt()            { return committedAt; }
    public BigDecimal refundAmountKrw()     { return refundAmountKrw; }
    public String qrCodeId()                { return qrCodeId; }
    public Instant refundedAt()             { return refundedAt; }
    public String originalPaymentTxnRef()   { return originalPaymentTxnRef; }

    // Wave-3 (V008) rate-lock pool accessors
    public BigDecimal collectionUsd()       { return collectionUsd; }
    public BigDecimal costRateColl()        { return costRateColl; }
    public BigDecimal costRatePay()         { return costRatePay; }
    public BigDecimal payoutUsdCost()       { return payoutUsdCost; }

    // V009 (Ops) operator-resolution audit accessors
    public String resolutionReason()        { return resolutionReason; }
    public String resolvedBy()              { return resolvedBy; }
    public Instant resolvedAt()             { return resolvedAt; }
}
