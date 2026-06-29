package com.gme.pay.settlement.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping {@code settlement_lines}.
 *
 * <p>One row per transaction included in a settlement batch. The {@code matched} flag
 * is updated when the corresponding scheme-confirmed amount is reconciled.
 */
@Entity
@Table(name = "settlement_lines")
public class SettlementLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "batch_id", length = 64, nullable = false)
    private String batchId;

    @Column(name = "txn_ref", length = 64)
    private String txnRef;

    @Column(name = "amount", precision = 20, scale = 8, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "matched", nullable = false)
    private boolean matched;

    // ----- Outbound booking snapshot (V006) -----

    @Column(name = "booked_settlement_amount", precision = 20, scale = 4)
    private BigDecimal bookedSettlementAmount;

    @Column(name = "rounding_residual", precision = 20, scale = 8)
    private BigDecimal roundingResidual;

    @Column(name = "settlement_rounding_mode", length = 16)
    private String settlementRoundingMode;

    @Column(name = "settlement_type", length = 1)
    private String settlementType;           // 'N' | 'G'

    // ----- Detail-file snapshot (V008) -----
    // Captured at request-window write time so the ZP0065/ZP0066 detail run builds rows from the line
    // alone — the authoritative settled record — without re-fetching (or re-rounding) the live txn.

    @Column(name = "merchant_id", length = 64)
    private String merchantId;

    @Column(name = "scheme_ref", length = 64)
    private String schemeRef;

    @Column(name = "approved_at")
    private Instant approvedAt;              // scheme approval instant (txn_time/date source)

    @Column(name = "merchant_fee_rate", precision = 9, scale = 6)
    private BigDecimal merchantFeeRate;     // V005 snapshot rate; 0 for GROSS

    public SettlementLineEntity() {
        // JPA no-arg constructor
    }

    public SettlementLineEntity(String batchId,
                                String txnRef,
                                BigDecimal amount,
                                String currency,
                                boolean matched) {
        this.batchId = batchId;
        this.txnRef = txnRef;
        this.amount = amount;
        this.currency = currency;
        this.matched = matched;
    }

    public BigDecimal getBookedSettlementAmount() { return bookedSettlementAmount; }
    public void setBookedSettlementAmount(BigDecimal bookedSettlementAmount) { this.bookedSettlementAmount = bookedSettlementAmount; }

    public BigDecimal getRoundingResidual() { return roundingResidual; }
    public void setRoundingResidual(BigDecimal roundingResidual) { this.roundingResidual = roundingResidual; }

    public String getSettlementRoundingMode() { return settlementRoundingMode; }
    public void setSettlementRoundingMode(String settlementRoundingMode) { this.settlementRoundingMode = settlementRoundingMode; }

    public String getSettlementType() { return settlementType; }
    public void setSettlementType(String settlementType) { this.settlementType = settlementType; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getSchemeRef() { return schemeRef; }
    public void setSchemeRef(String schemeRef) { this.schemeRef = schemeRef; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public BigDecimal getMerchantFeeRate() { return merchantFeeRate; }
    public void setMerchantFeeRate(BigDecimal merchantFeeRate) { this.merchantFeeRate = merchantFeeRate; }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getTxnRef() {
        return txnRef;
    }

    public void setTxnRef(String txnRef) {
        this.txnRef = txnRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isMatched() {
        return matched;
    }

    public void setMatched(boolean matched) {
        this.matched = matched;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SettlementLineEntity that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
