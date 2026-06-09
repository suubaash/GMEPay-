package com.gme.pay.txn.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity mapped to the {@code transactions} table created by Flyway V001.
 *
 * <p>This is a persistence-layer adapter type – kept deliberately separate from the
 * domain aggregate {@link com.gme.pay.txn.domain.model.Transaction} so the domain
 * model stays free of {@code jakarta.persistence} annotations and unit-testable.
 *
 * <p>Money columns use {@link BigDecimal} (NUMERIC(20,8)) per MONEY_CONVENTION.md.
 * The three rate-lock columns are nullable until commit; mapping them as nullable
 * here lets us persist in-flight (CREATED / PENDING_DEBIT) transactions.
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
}
