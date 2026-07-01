package com.gme.pay.reporting.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * JPA entity for {@code bok_report_record} (V002) — one persisted BOK FX1014/FX1015
 * record per cross-border committed transaction.
 *
 * <p>{@code offerRateColl} is BOK FX1015 field #14, carried verbatim from the
 * committed-transaction FX source (never recomputed here). {@code txnId} is UNIQUE,
 * so re-processing the same transaction is a no-op.
 */
@Entity
@Table(name = "bok_report_record",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_bok_record_txn", columnNames = "txn_id"))
public class BokReportRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "filing_id")
    private Long filingId;

    @Column(name = "txn_id", nullable = false)
    private long txnId;

    @Column(name = "txn_ref", nullable = false, length = 64)
    private String txnRef;

    @Column(name = "report_type", nullable = false, length = 10)
    private String reportType;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "partner_id", nullable = false)
    private long partnerId;

    @Column(name = "collection_amount", precision = 20, scale = 4)
    private BigDecimal collectionAmount;

    @Column(name = "collection_ccy", length = 3)
    private String collectionCcy;

    @Column(name = "payout_amount", precision = 20, scale = 4)
    private BigDecimal payoutAmount;

    @Column(name = "payout_ccy", length = 3)
    private String payoutCcy;

    /** BOK FX1015 field #14. Precision matches DECIMAL(20,8). */
    @Column(name = "offer_rate_coll", precision = 20, scale = 8)
    private BigDecimal offerRateColl;

    @Column(name = "cross_rate", precision = 20, scale = 8)
    private BigDecimal crossRate;

    @Column(name = "usd_amount", precision = 20, scale = 4)
    private BigDecimal usdAmount;

    @Column(name = "submission_status", nullable = false, length = 20)
    private String submissionStatus;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BokReportRecordEntity() {
        // JPA
    }

    public BokReportRecordEntity(
            Long filingId,
            long txnId,
            String txnRef,
            String reportType,
            LocalDate reportDate,
            long partnerId,
            BigDecimal collectionAmount,
            String collectionCcy,
            BigDecimal payoutAmount,
            String payoutCcy,
            BigDecimal offerRateColl,
            BigDecimal crossRate,
            BigDecimal usdAmount,
            String submissionStatus) {
        this.filingId = filingId;
        this.txnId = txnId;
        this.txnRef = txnRef;
        this.reportType = reportType;
        this.reportDate = reportDate;
        this.partnerId = partnerId;
        this.collectionAmount = collectionAmount;
        this.collectionCcy = collectionCcy;
        this.payoutAmount = payoutAmount;
        this.payoutCcy = payoutCcy;
        this.offerRateColl = offerRateColl;
        this.crossRate = crossRate;
        this.usdAmount = usdAmount;
        this.submissionStatus = submissionStatus;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getFilingId() { return filingId; }
    public long getTxnId() { return txnId; }
    public String getTxnRef() { return txnRef; }
    public String getReportType() { return reportType; }
    public LocalDate getReportDate() { return reportDate; }
    public long getPartnerId() { return partnerId; }
    public BigDecimal getCollectionAmount() { return collectionAmount; }
    public String getCollectionCcy() { return collectionCcy; }
    public BigDecimal getPayoutAmount() { return payoutAmount; }
    public String getPayoutCcy() { return payoutCcy; }
    public BigDecimal getOfferRateColl() { return offerRateColl; }
    public BigDecimal getCrossRate() { return crossRate; }
    public BigDecimal getUsdAmount() { return usdAmount; }
    public String getSubmissionStatus() { return submissionStatus; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
