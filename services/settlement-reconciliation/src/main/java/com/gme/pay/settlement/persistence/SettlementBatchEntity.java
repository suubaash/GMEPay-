package com.gme.pay.settlement.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping {@code settlement_batches}.
 *
 * <p>This is the settlement-reconciliation service's private persistence row for a ZP006x
 * batch lifecycle entry. Owned exclusively by this service per MSA rules.
 */
@Entity
@Table(name = "settlement_batches")
public class SettlementBatchEntity {

    @Id
    @Column(name = "batch_id", length = 64, nullable = false)
    private String batchId;

    @Column(name = "partner_id", length = 32, nullable = false)
    private String partnerId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "total_amount", precision = 20, scale = 8)
    private BigDecimal totalAmount;

    @Column(name = "total_currency", length = 3)
    private String totalCurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SettlementBatchEntity() {
        // JPA no-arg constructor
    }

    public SettlementBatchEntity(String batchId,
                                 String partnerId,
                                 LocalDate businessDate,
                                 String status,
                                 BigDecimal totalAmount,
                                 String totalCurrency,
                                 Instant createdAt) {
        this.batchId = batchId;
        this.partnerId = partnerId;
        this.businessDate = businessDate;
        this.status = status;
        this.totalAmount = totalAmount;
        this.totalCurrency = totalCurrency;
        this.createdAt = createdAt;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(String partnerId) {
        this.partnerId = partnerId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getTotalCurrency() {
        return totalCurrency;
    }

    public void setTotalCurrency(String totalCurrency) {
        this.totalCurrency = totalCurrency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SettlementBatchEntity that)) return false;
        return Objects.equals(batchId, that.batchId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchId);
    }
}
