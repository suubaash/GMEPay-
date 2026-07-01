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

    /**
     * BIGINT surrogate partner FK introduced by V004 (Slice 1 schism resolution, Expand
     * phase per ADR-013). Nullable for now: application writes from Slice 2 onwards
     * will populate it alongside {@link #partnerId}; a future Contract migration drops
     * the String column and promotes this one. Until then, leaving it null on legacy
     * writes is intentional — the read path still uses the String column.
     */
    @Column(name = "partner_id_new")
    private Long partnerIdNew;

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

    // ----- Outbound settlement-file lifecycle (V006) -----

    @Column(name = "file_type", length = 8)
    private String fileType;                 // 'ZP0061' | 'ZP0063' | 'ZP0065' | 'ZP0066'

    @Column(name = "direction", length = 16)
    private String direction;                // 'GME_TO_ZP'

    @Column(name = "settlement_window", length = 16)
    private String settlementWindow;         // 'MORNING' | 'AFTERNOON' | 'DETAIL'

    @Column(name = "settlement_type", length = 1)
    private String settlementType;           // 'N' | 'G' | null (mixed multi-merchant batch)

    @Column(name = "net_settlement_amount", precision = 20, scale = 4)
    private BigDecimal netSettlementAmount;

    @Column(name = "merchant_fee_total", precision = 20, scale = 4)
    private BigDecimal merchantFeeTotal;

    @Column(name = "rounding_residual", precision = 20, scale = 8)
    private BigDecimal roundingResidual;     // Addendum-001 residual (full precision)

    @Column(name = "settlement_rounding_mode", length = 16)
    private String settlementRoundingMode;

    @Column(name = "settle_currency", length = 3)
    private String settleCurrency;

    @Column(name = "file_checksum", length = 64)
    private String fileChecksum;             // SHA-256 hex

    @Column(name = "record_count")
    private Integer recordCount;

    @Column(name = "transmitted_at")
    private Instant transmittedAt;           // set by the SFTP task (later)

    @Column(name = "error_detail", length = 1024)
    private String errorDetail;

    /**
     * Set once the batch's {@link #roundingResidual} has been POSTed to revenue-ledger
     * ({@code /v1/journals/rounding-residual}). Once-per-batch guard (V009): a recon re-run that finds
     * this non-null never re-posts. Null = not yet posted.
     */
    @Column(name = "residual_posted_at")
    private Instant residualPostedAt;

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

    /** BIGINT surrogate FK (V004). See {@link #partnerIdNew} for Expand-phase semantics. */
    public Long getPartnerIdNew() {
        return partnerIdNew;
    }

    public void setPartnerIdNew(Long partnerIdNew) {
        this.partnerIdNew = partnerIdNew;
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

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getSettlementWindow() { return settlementWindow; }
    public void setSettlementWindow(String settlementWindow) { this.settlementWindow = settlementWindow; }

    public String getSettlementType() { return settlementType; }
    public void setSettlementType(String settlementType) { this.settlementType = settlementType; }

    public BigDecimal getNetSettlementAmount() { return netSettlementAmount; }
    public void setNetSettlementAmount(BigDecimal netSettlementAmount) { this.netSettlementAmount = netSettlementAmount; }

    public BigDecimal getMerchantFeeTotal() { return merchantFeeTotal; }
    public void setMerchantFeeTotal(BigDecimal merchantFeeTotal) { this.merchantFeeTotal = merchantFeeTotal; }

    public BigDecimal getRoundingResidual() { return roundingResidual; }
    public void setRoundingResidual(BigDecimal roundingResidual) { this.roundingResidual = roundingResidual; }

    public String getSettlementRoundingMode() { return settlementRoundingMode; }
    public void setSettlementRoundingMode(String settlementRoundingMode) { this.settlementRoundingMode = settlementRoundingMode; }

    public String getSettleCurrency() { return settleCurrency; }
    public void setSettleCurrency(String settleCurrency) { this.settleCurrency = settleCurrency; }

    public String getFileChecksum() { return fileChecksum; }
    public void setFileChecksum(String fileChecksum) { this.fileChecksum = fileChecksum; }

    public Integer getRecordCount() { return recordCount; }
    public void setRecordCount(Integer recordCount) { this.recordCount = recordCount; }

    public Instant getTransmittedAt() { return transmittedAt; }
    public void setTransmittedAt(Instant transmittedAt) { this.transmittedAt = transmittedAt; }

    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }

    public Instant getResidualPostedAt() { return residualPostedAt; }
    public void setResidualPostedAt(Instant residualPostedAt) { this.residualPostedAt = residualPostedAt; }

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
