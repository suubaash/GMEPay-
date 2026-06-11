package com.gme.pay.payment.persistence;

import com.gme.pay.payment.domain.Direction;
import com.gme.pay.payment.domain.PaymentMode;
import com.gme.pay.payment.domain.PaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * One row per payment-orchestration attempt (17.2-G08, table {@code execution_attempts}).
 *
 * <p>Records the outcome of each run through {@code PaymentOrchestrator} — including, for
 * APPROVED attempts, a snapshot of the rate-locked per-partner settlement booking
 * ({@link #getBookedSettlementAmount() booked amount}, the {@link RoundingMode} actually
 * applied and the {@link #getRoundingResidual() residual} posted to revenue-ledger).
 * Money fields are always {@link BigDecimal} mapped to {@code NUMERIC(20,8)} per
 * {@code docs/MONEY_CONVENTION.md}; schema is owned by Flyway (V001), never by Hibernate.
 */
@Entity
@Table(name = "execution_attempts")
public class ExecutionAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_ref", nullable = false, length = 64)
    private String txnRef;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "partner_id", nullable = false)
    private long partnerId;

    @Column(name = "partner_txn_ref", nullable = false, length = 64)
    private String partnerTxnRef;

    @Column(name = "scheme_id", nullable = false, length = 32)
    private String schemeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 5)
    private PaymentMode paymentMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 16)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 24)
    private PaymentStatus outcome;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "scheme_txn_ref", length = 64)
    private String schemeTxnRef;

    @Column(name = "prefund_deducted_usd", precision = 20, scale = 8)
    private BigDecimal prefundDeductedUsd;

    @Column(name = "booked_settlement_amount", precision = 20, scale = 8)
    private BigDecimal bookedSettlementAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_rounding_mode", length = 16)
    private RoundingMode settlementRoundingMode;

    @Column(name = "rounding_residual", precision = 20, scale = 8)
    private BigDecimal roundingResidual;

    @Column(name = "settlement_currency", length = 3)
    private String settlementCurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** JPA only. */
    protected ExecutionAttemptEntity() {
    }

    /** Creates an attempt with the fields known at orchestration start. */
    public ExecutionAttemptEntity(String txnRef,
                                  long partnerId,
                                  String partnerTxnRef,
                                  String schemeId,
                                  PaymentMode paymentMode,
                                  PaymentStatus outcome,
                                  Instant createdAt) {
        this.txnRef = txnRef;
        this.partnerId = partnerId;
        this.partnerTxnRef = partnerTxnRef;
        this.schemeId = schemeId;
        this.paymentMode = paymentMode;
        this.outcome = outcome;
        this.createdAt = createdAt;
    }

    // ---- getters ----

    public Long getId() {
        return id;
    }

    public String getTxnRef() {
        return txnRef;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public long getPartnerId() {
        return partnerId;
    }

    public String getPartnerTxnRef() {
        return partnerTxnRef;
    }

    public String getSchemeId() {
        return schemeId;
    }

    public PaymentMode getPaymentMode() {
        return paymentMode;
    }

    public Direction getDirection() {
        return direction;
    }

    public PaymentStatus getOutcome() {
        return outcome;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getSchemeTxnRef() {
        return schemeTxnRef;
    }

    public BigDecimal getPrefundDeductedUsd() {
        return prefundDeductedUsd;
    }

    public BigDecimal getBookedSettlementAmount() {
        return bookedSettlementAmount;
    }

    public RoundingMode getSettlementRoundingMode() {
        return settlementRoundingMode;
    }

    public BigDecimal getRoundingResidual() {
        return roundingResidual;
    }

    public String getSettlementCurrency() {
        return settlementCurrency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    // ---- setters for fields learned during / after orchestration ----

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public void setOutcome(PaymentStatus outcome) {
        this.outcome = outcome;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public void setSchemeTxnRef(String schemeTxnRef) {
        this.schemeTxnRef = schemeTxnRef;
    }

    public void setPrefundDeductedUsd(BigDecimal prefundDeductedUsd) {
        this.prefundDeductedUsd = prefundDeductedUsd;
    }

    /**
     * Snapshots the rate-locked settlement booking (see {@code SettlementBooking}):
     * booked partner liability, the rounding mode actually applied, the residual
     * (precise - booked) and the booking currency.
     */
    public void setSettlementSnapshot(BigDecimal bookedSettlementAmount,
                                      RoundingMode settlementRoundingMode,
                                      BigDecimal roundingResidual,
                                      String settlementCurrency) {
        this.bookedSettlementAmount = bookedSettlementAmount;
        this.settlementRoundingMode = settlementRoundingMode;
        this.roundingResidual = roundingResidual;
        this.settlementCurrency = settlementCurrency;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
