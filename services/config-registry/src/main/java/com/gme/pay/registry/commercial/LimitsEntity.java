package com.gme.pay.registry.commercial;

import com.gme.pay.contracts.LimitsView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_limits} table (V020) — the
 * per-transaction and rolling aggregate caps of a partner, bitemporally
 * versioned per ADR-010 (Slice 6 — Commercial Terms).
 *
 * <p>Same SCD-6 paired-write discipline as {@code PrefundingConfigEntity}
 * (V015): one current row per partner, never UPDATEd in place — see
 * {@link LimitsService}, which also hard-enforces the 소액해외송금업
 * ({@code SOAEK_HAEOEMONG}) statutory caps the V020 CHECK backstops.
 */
@Entity
@Table(name = "partner_limits")
public class LimitsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** Per-transaction floor, major USD units; NULL = unconstrained. */
    @Column(name = "per_txn_min_usd", precision = 19, scale = 4)
    private BigDecimal perTxnMinUsd;

    /** Per-transaction ceiling, major USD units; NULL = unconstrained. */
    @Column(name = "per_txn_max_usd", precision = 19, scale = 4)
    private BigDecimal perTxnMaxUsd;

    /** Rolling daily cap; NULL = unconstrained. */
    @Column(name = "daily_cap_usd", precision = 19, scale = 4)
    private BigDecimal dailyCapUsd;

    /** Rolling monthly cap; NULL = unconstrained. */
    @Column(name = "monthly_cap_usd", precision = 19, scale = 4)
    private BigDecimal monthlyCapUsd;

    /** Rolling annual cap; NULL = unconstrained. */
    @Column(name = "annual_cap_usd", precision = 19, scale = 4)
    private BigDecimal annualCapUsd;

    /** Regulatory regime discriminator (e.g. SOAEK_HAEOEMONG); NULL = generic. */
    @Column(name = "license_type", length = 30)
    private String licenseType;

    /** Business-time lower bound (inclusive), ADR-010. */
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** Business-time upper bound (exclusive); NULL = open-ended. */
    @Column(name = "valid_to")
    private Instant validTo;

    /** Transaction-time: when this row was recorded. Never NULL. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    /** Transaction-time: when this row stopped being current; NULL on current rows. */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    public LimitsEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = recordedAt;
        }
    }

    /** Adapt this row to the canonical {@link LimitsView} wire DTO. */
    public LimitsView toView() {
        return new LimitsView(
                id,
                perTxnMinUsd,
                perTxnMaxUsd,
                dailyCapUsd,
                monthlyCapUsd,
                annualCapUsd,
                licenseType,
                validFrom,
                validTo,
                recordedAt);
    }

    public Long getId() {
        return id;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(Long partnerId) {
        this.partnerId = partnerId;
    }

    public BigDecimal getPerTxnMinUsd() {
        return perTxnMinUsd;
    }

    public void setPerTxnMinUsd(BigDecimal perTxnMinUsd) {
        this.perTxnMinUsd = perTxnMinUsd;
    }

    public BigDecimal getPerTxnMaxUsd() {
        return perTxnMaxUsd;
    }

    public void setPerTxnMaxUsd(BigDecimal perTxnMaxUsd) {
        this.perTxnMaxUsd = perTxnMaxUsd;
    }

    public BigDecimal getDailyCapUsd() {
        return dailyCapUsd;
    }

    public void setDailyCapUsd(BigDecimal dailyCapUsd) {
        this.dailyCapUsd = dailyCapUsd;
    }

    public BigDecimal getMonthlyCapUsd() {
        return monthlyCapUsd;
    }

    public void setMonthlyCapUsd(BigDecimal monthlyCapUsd) {
        this.monthlyCapUsd = monthlyCapUsd;
    }

    public BigDecimal getAnnualCapUsd() {
        return annualCapUsd;
    }

    public void setAnnualCapUsd(BigDecimal annualCapUsd) {
        this.annualCapUsd = annualCapUsd;
    }

    public String getLicenseType() {
        return licenseType;
    }

    public void setLicenseType(String licenseType) {
        this.licenseType = licenseType;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public void setValidTo(Instant validTo) {
        this.validTo = validTo;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public void setSupersededAt(Instant supersededAt) {
        this.supersededAt = supersededAt;
    }
}
