package com.gme.pay.settlement.persistence;

import com.gme.pay.settlement.recon.MatchStatus;
import com.gme.pay.settlement.recon.ReconLine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping {@code recon_exceptions}.
 *
 * <p>Persists the outcome of a {@link com.gme.pay.settlement.recon.LineMatcher} run for a
 * settlement batch. Amounts are stored as {@code NUMERIC(20,8)} per the money convention
 * (BigDecimal end-to-end; PostgreSQL pads to the declared scale of 8 without losing value).
 * {@code schemeAmount} is {@code null} when the scheme file had no entry for the merchant
 * ({@link MatchStatus#MISSING_SCHEME}).
 */
@Entity
@Table(name = "recon_exceptions")
public class ReconExceptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "batch_id", length = 64, nullable = false)
    private String batchId;

    @Column(name = "merchant_id", length = 64, nullable = false)
    private String merchantId;

    @Column(name = "gme_amount", precision = 20, scale = 8, nullable = false)
    private BigDecimal gmeAmount;

    @Column(name = "scheme_amount", precision = 20, scale = 8)
    private BigDecimal schemeAmount;

    @Column(name = "discrepancy_amount", precision = 20, scale = 8, nullable = false)
    private BigDecimal discrepancyAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", length = 32, nullable = false)
    private MatchStatus matchStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ReconExceptionEntity() {
        // JPA no-arg constructor
    }

    public ReconExceptionEntity(String batchId,
                                String merchantId,
                                BigDecimal gmeAmount,
                                BigDecimal schemeAmount,
                                BigDecimal discrepancyAmount,
                                MatchStatus matchStatus,
                                Instant createdAt) {
        this.batchId = batchId;
        this.merchantId = merchantId;
        this.gmeAmount = gmeAmount;
        this.schemeAmount = schemeAmount;
        this.discrepancyAmount = discrepancyAmount;
        this.matchStatus = matchStatus;
        this.createdAt = createdAt;
    }

    /** Build a persistable row from a {@link LineMatcher} result line. */
    public static ReconExceptionEntity fromReconLine(String batchId, ReconLine line, Instant createdAt) {
        return new ReconExceptionEntity(
                batchId,
                line.merchantId(),
                line.gmeAmount(),
                line.schemeAmount(),
                line.discrepancyAmount(),
                line.matchStatus(),
                createdAt);
    }

    /** Reconstruct the domain-level {@link ReconLine} from the persisted row. */
    public ReconLine toReconLine() {
        return new ReconLine(merchantId, gmeAmount, schemeAmount, discrepancyAmount, matchStatus);
    }

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

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public BigDecimal getGmeAmount() {
        return gmeAmount;
    }

    public void setGmeAmount(BigDecimal gmeAmount) {
        this.gmeAmount = gmeAmount;
    }

    public BigDecimal getSchemeAmount() {
        return schemeAmount;
    }

    public void setSchemeAmount(BigDecimal schemeAmount) {
        this.schemeAmount = schemeAmount;
    }

    public BigDecimal getDiscrepancyAmount() {
        return discrepancyAmount;
    }

    public void setDiscrepancyAmount(BigDecimal discrepancyAmount) {
        this.discrepancyAmount = discrepancyAmount;
    }

    public MatchStatus getMatchStatus() {
        return matchStatus;
    }

    public void setMatchStatus(MatchStatus matchStatus) {
        this.matchStatus = matchStatus;
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
        if (!(o instanceof ReconExceptionEntity that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
