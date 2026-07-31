package com.gme.pay.settlement.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping {@code corridor_recon_summary} (Flyway V011) — the per-day, per-scheme
 * cross-border reconciliation summary finance reads.
 *
 * <p>One row per {@code (settlementDate, scheme)}; a recon re-run of the same date overwrites it, so
 * the summary is idempotent exactly like the exception rows beside it.
 *
 * <p>{@code rateBasisVarianceUsd} is SIGNED (USD deducted from the float − USD owed the scheme at
 * its registered rate) and {@code cumulativeVarianceUsd} is the running signed total through this
 * date — the number that makes a small systematic rate-basis loss visible before it compounds.
 */
@Entity
@Table(name = "corridor_recon_summary")
public class CorridorReconSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "scheme", length = 32, nullable = false)
    private String scheme;

    @Column(name = "corridor", length = 32, nullable = false)
    private String corridor;

    @Column(name = "batch_id", length = 64, nullable = false)
    private String batchId;

    @Column(name = "txn_count", nullable = false)
    private int txnCount;

    @Column(name = "charged_krw", precision = 20, scale = 8, nullable = false)
    private BigDecimal chargedKrw = BigDecimal.ZERO;

    @Column(name = "local_paid", precision = 20, scale = 8, nullable = false)
    private BigDecimal localPaid = BigDecimal.ZERO;

    @Column(name = "local_currency", length = 3, nullable = false)
    private String localCurrency;

    @Column(name = "usd_deducted", precision = 20, scale = 8, nullable = false)
    private BigDecimal usdDeducted = BigDecimal.ZERO;

    @Column(name = "usd_owed_scheme", precision = 20, scale = 8, nullable = false)
    private BigDecimal usdOwedScheme = BigDecimal.ZERO;

    /** SIGNED: usdDeducted − usdOwedScheme for the day. Negative = USD shortfall (a real loss). */
    @Column(name = "rate_basis_variance_usd", precision = 20, scale = 8, nullable = false)
    private BigDecimal rateBasisVarianceUsd = BigDecimal.ZERO;

    /** SIGNED running total of {@link #rateBasisVarianceUsd} across all summarised days ≤ this one. */
    @Column(name = "cumulative_variance_usd", precision = 20, scale = 8, nullable = false)
    private BigDecimal cumulativeVarianceUsd = BigDecimal.ZERO;

    @Column(name = "fallback_rate_basis_count", nullable = false)
    private int fallbackRateBasisCount;

    @Column(name = "break_count", nullable = false)
    private int breakCount;

    @Column(name = "break_value_usd", precision = 20, scale = 8, nullable = false)
    private BigDecimal breakValueUsd = BigDecimal.ZERO;

    /** True only once a REAL partner settlement file has been parsed. False today for every scheme. */
    @Column(name = "scheme_feed_available", nullable = false)
    private boolean schemeFeedAvailable;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    public CorridorReconSummaryEntity() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public void setSettlementDate(LocalDate settlementDate) {
        this.settlementDate = settlementDate;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getCorridor() {
        return corridor;
    }

    public void setCorridor(String corridor) {
        this.corridor = corridor;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public int getTxnCount() {
        return txnCount;
    }

    public void setTxnCount(int txnCount) {
        this.txnCount = txnCount;
    }

    public BigDecimal getChargedKrw() {
        return chargedKrw;
    }

    public void setChargedKrw(BigDecimal chargedKrw) {
        this.chargedKrw = chargedKrw;
    }

    public BigDecimal getLocalPaid() {
        return localPaid;
    }

    public void setLocalPaid(BigDecimal localPaid) {
        this.localPaid = localPaid;
    }

    public String getLocalCurrency() {
        return localCurrency;
    }

    public void setLocalCurrency(String localCurrency) {
        this.localCurrency = localCurrency;
    }

    public BigDecimal getUsdDeducted() {
        return usdDeducted;
    }

    public void setUsdDeducted(BigDecimal usdDeducted) {
        this.usdDeducted = usdDeducted;
    }

    public BigDecimal getUsdOwedScheme() {
        return usdOwedScheme;
    }

    public void setUsdOwedScheme(BigDecimal usdOwedScheme) {
        this.usdOwedScheme = usdOwedScheme;
    }

    public BigDecimal getRateBasisVarianceUsd() {
        return rateBasisVarianceUsd;
    }

    public void setRateBasisVarianceUsd(BigDecimal rateBasisVarianceUsd) {
        this.rateBasisVarianceUsd = rateBasisVarianceUsd;
    }

    public BigDecimal getCumulativeVarianceUsd() {
        return cumulativeVarianceUsd;
    }

    public void setCumulativeVarianceUsd(BigDecimal cumulativeVarianceUsd) {
        this.cumulativeVarianceUsd = cumulativeVarianceUsd;
    }

    public int getFallbackRateBasisCount() {
        return fallbackRateBasisCount;
    }

    public void setFallbackRateBasisCount(int fallbackRateBasisCount) {
        this.fallbackRateBasisCount = fallbackRateBasisCount;
    }

    public int getBreakCount() {
        return breakCount;
    }

    public void setBreakCount(int breakCount) {
        this.breakCount = breakCount;
    }

    public BigDecimal getBreakValueUsd() {
        return breakValueUsd;
    }

    public void setBreakValueUsd(BigDecimal breakValueUsd) {
        this.breakValueUsd = breakValueUsd;
    }

    public boolean isSchemeFeedAvailable() {
        return schemeFeedAvailable;
    }

    public void setSchemeFeedAvailable(boolean schemeFeedAvailable) {
        this.schemeFeedAvailable = schemeFeedAvailable;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CorridorReconSummaryEntity that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
