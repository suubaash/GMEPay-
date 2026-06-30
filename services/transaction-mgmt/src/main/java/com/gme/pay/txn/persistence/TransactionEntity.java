package com.gme.pay.txn.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity mapped to the {@code transactions} table created by Flyway V001/V003.
 *
 * <p>This is a persistence-layer adapter type – kept deliberately separate from the
 * domain aggregate {@link com.gme.pay.txn.domain.model.Transaction} so the domain
 * model stays free of {@code jakarta.persistence} annotations and unit-testable.
 *
 * <p>Money columns use {@link BigDecimal} (NUMERIC(20,8)) per MONEY_CONVENTION.md.
 * The three rate-lock columns are nullable until commit; mapping them as nullable
 * here lets us persist in-flight (CREATED / PENDING_DEBIT) transactions.
 *
 * <p>V003 columns: payment-executor 11-field create path + 5 status-patch lock fields.
 */
@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    @Column(name = "txn_ref", length = 64, nullable = false, updatable = false)
    private String txnRef;

    @Column(name = "partner_ref", length = 64, nullable = false)
    private String partnerRef;

    @Column(name = "send_amount", precision = 20, scale = 8, nullable = false)
    private BigDecimal sendAmount;

    @Column(name = "send_ccy", length = 3, nullable = false)
    private String sendCcy;

    @Column(name = "target_payout", precision = 20, scale = 8, nullable = false)
    private BigDecimal targetPayout;

    @Column(name = "target_ccy", length = 3, nullable = false)
    private String targetCcy;

    @Column(name = "status", length = 24, nullable = false)
    private String status;

    @Column(name = "booked_settlement_amount", precision = 20, scale = 8)
    private BigDecimal bookedSettlementAmount;

    @Column(name = "settlement_rounding_mode", length = 16)
    private String settlementRoundingMode;

    @Column(name = "rounding_residual", precision = 20, scale = 8)
    private BigDecimal roundingResidual;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // --- V003: payment-executor 11-field create path ---

    @Column(name = "partner_id")
    private Long partnerId;

    @Column(name = "partner_txn_ref", length = 128)
    private String partnerTxnRef;

    @Column(name = "scheme_id", length = 64)
    private String schemeId;

    @Column(name = "direction", length = 32)
    private String direction;

    @Column(name = "payment_mode", length = 32)
    private String paymentMode;

    @Column(name = "payout_currency", length = 3)
    private String payoutCurrency;

    @Column(name = "collection_amount", precision = 20, scale = 8)
    private BigDecimal collectionAmount;

    @Column(name = "collection_currency", length = 3)
    private String collectionCurrency;

    @Column(name = "merchant_id", length = 128)
    private String merchantId;

    /** V005: gross merchant fee rate snapshotted at creation (NUMERIC(7,4), e.g. 0.0080 = 0.80%). */
    @Column(name = "merchant_fee_rate", precision = 7, scale = 4)
    private BigDecimal merchantFeeRate;

    @Column(name = "quote_id", length = 128)
    private String quoteId;

    // --- V003: status-patch lock fields ---

    @Column(name = "scheme_txn_ref", length = 128)
    private String schemeTxnRef;

    @Column(name = "scheme_approval_code", length = 64)
    private String schemeApprovalCode;

    @Column(name = "prefund_deducted_usd", precision = 20, scale = 8)
    private BigDecimal prefundDeductedUsd;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    /** Reason code for terminal FAILED transitions (e.g. "APPROVAL_TIMEOUT"). Nullable. */
    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    // --- V007: committed-FX projection columns (captured best-effort at commit) ---

    @Column(name = "offer_rate_coll", precision = 20, scale = 8)
    private BigDecimal offerRateColl;

    @Column(name = "cross_rate", precision = 20, scale = 8)
    private BigDecimal crossRate;

    @Column(name = "collection_margin_usd", precision = 20, scale = 8)
    private BigDecimal collectionMarginUsd;

    @Column(name = "payout_margin_usd", precision = 20, scale = 8)
    private BigDecimal payoutMarginUsd;

    @Column(name = "usd_amount", precision = 20, scale = 8)
    private BigDecimal usdAmount;

    @Column(name = "same_ccy_shortcircuit")
    private Boolean sameCcyShortcircuit;

    @Column(name = "settlement_date")
    private java.time.LocalDate settlementDate;

    @Column(name = "committed_at")
    private Instant committedAt;

    // --- V007: refund enrichment columns ---

    @Column(name = "refund_amount_krw", precision = 20, scale = 8)
    private BigDecimal refundAmountKrw;

    @Column(name = "qr_code_id", length = 64)
    private String qrCodeId;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "original_payment_txn_ref", length = 128)
    private String originalPaymentTxnRef;

    /** Required no-arg constructor for JPA. */
    public TransactionEntity() {}

    // --- accessors / mutators (plain JavaBean, no Lombok) ---

    public String getTxnRef() { return txnRef; }
    public void setTxnRef(String txnRef) { this.txnRef = txnRef; }

    public String getPartnerRef() { return partnerRef; }
    public void setPartnerRef(String partnerRef) { this.partnerRef = partnerRef; }

    public BigDecimal getSendAmount() { return sendAmount; }
    public void setSendAmount(BigDecimal sendAmount) { this.sendAmount = sendAmount; }

    public String getSendCcy() { return sendCcy; }
    public void setSendCcy(String sendCcy) { this.sendCcy = sendCcy; }

    public BigDecimal getTargetPayout() { return targetPayout; }
    public void setTargetPayout(BigDecimal targetPayout) { this.targetPayout = targetPayout; }

    public String getTargetCcy() { return targetCcy; }
    public void setTargetCcy(String targetCcy) { this.targetCcy = targetCcy; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getBookedSettlementAmount() { return bookedSettlementAmount; }
    public void setBookedSettlementAmount(BigDecimal bookedSettlementAmount) {
        this.bookedSettlementAmount = bookedSettlementAmount;
    }

    public String getSettlementRoundingMode() { return settlementRoundingMode; }
    public void setSettlementRoundingMode(String settlementRoundingMode) {
        this.settlementRoundingMode = settlementRoundingMode;
    }

    public BigDecimal getRoundingResidual() { return roundingResidual; }
    public void setRoundingResidual(BigDecimal roundingResidual) {
        this.roundingResidual = roundingResidual;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long partnerId) { this.partnerId = partnerId; }

    public String getPartnerTxnRef() { return partnerTxnRef; }
    public void setPartnerTxnRef(String partnerTxnRef) { this.partnerTxnRef = partnerTxnRef; }

    public String getSchemeId() { return schemeId; }
    public void setSchemeId(String schemeId) { this.schemeId = schemeId; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }

    public String getPayoutCurrency() { return payoutCurrency; }
    public void setPayoutCurrency(String payoutCurrency) { this.payoutCurrency = payoutCurrency; }

    public BigDecimal getCollectionAmount() { return collectionAmount; }
    public void setCollectionAmount(BigDecimal collectionAmount) { this.collectionAmount = collectionAmount; }

    public String getCollectionCurrency() { return collectionCurrency; }
    public void setCollectionCurrency(String collectionCurrency) { this.collectionCurrency = collectionCurrency; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public BigDecimal getMerchantFeeRate() { return merchantFeeRate; }
    public void setMerchantFeeRate(BigDecimal merchantFeeRate) { this.merchantFeeRate = merchantFeeRate; }

    public String getQuoteId() { return quoteId; }
    public void setQuoteId(String quoteId) { this.quoteId = quoteId; }

    public String getSchemeTxnRef() { return schemeTxnRef; }
    public void setSchemeTxnRef(String schemeTxnRef) { this.schemeTxnRef = schemeTxnRef; }

    public String getSchemeApprovalCode() { return schemeApprovalCode; }
    public void setSchemeApprovalCode(String schemeApprovalCode) { this.schemeApprovalCode = schemeApprovalCode; }

    public BigDecimal getPrefundDeductedUsd() { return prefundDeductedUsd; }
    public void setPrefundDeductedUsd(BigDecimal prefundDeductedUsd) { this.prefundDeductedUsd = prefundDeductedUsd; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    // --- V007 accessors ---

    public BigDecimal getOfferRateColl() { return offerRateColl; }
    public void setOfferRateColl(BigDecimal offerRateColl) { this.offerRateColl = offerRateColl; }

    public BigDecimal getCrossRate() { return crossRate; }
    public void setCrossRate(BigDecimal crossRate) { this.crossRate = crossRate; }

    public BigDecimal getCollectionMarginUsd() { return collectionMarginUsd; }
    public void setCollectionMarginUsd(BigDecimal collectionMarginUsd) { this.collectionMarginUsd = collectionMarginUsd; }

    public BigDecimal getPayoutMarginUsd() { return payoutMarginUsd; }
    public void setPayoutMarginUsd(BigDecimal payoutMarginUsd) { this.payoutMarginUsd = payoutMarginUsd; }

    public BigDecimal getUsdAmount() { return usdAmount; }
    public void setUsdAmount(BigDecimal usdAmount) { this.usdAmount = usdAmount; }

    public Boolean getSameCcyShortcircuit() { return sameCcyShortcircuit; }
    public void setSameCcyShortcircuit(Boolean sameCcyShortcircuit) { this.sameCcyShortcircuit = sameCcyShortcircuit; }

    public java.time.LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(java.time.LocalDate settlementDate) { this.settlementDate = settlementDate; }

    public Instant getCommittedAt() { return committedAt; }
    public void setCommittedAt(Instant committedAt) { this.committedAt = committedAt; }

    public BigDecimal getRefundAmountKrw() { return refundAmountKrw; }
    public void setRefundAmountKrw(BigDecimal refundAmountKrw) { this.refundAmountKrw = refundAmountKrw; }

    public String getQrCodeId() { return qrCodeId; }
    public void setQrCodeId(String qrCodeId) { this.qrCodeId = qrCodeId; }

    public Instant getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }

    public String getOriginalPaymentTxnRef() { return originalPaymentTxnRef; }
    public void setOriginalPaymentTxnRef(String originalPaymentTxnRef) { this.originalPaymentTxnRef = originalPaymentTxnRef; }
}
