package com.gme.pay.ratefx.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Treasury cost-rate snapshot captured at quote-issuance time (RATE-04 §3.2).
 * Convention: {@code usdRate} = units of {@code currencyCode} per 1 USD.
 *
 * <p>Rates are {@link BigDecimal} mapped to NUMERIC(20,8) per docs/MONEY_CONVENTION.md.
 * Schema owned by Flyway ({@code V001__create_rate_snapshots.sql}).
 */
@Entity
@Table(name = "rate_snapshots")
public class RateSnapshotEntity {

    @Id
    @Column(name = "snapshot_id", length = 64, nullable = false)
    private String snapshotId;

    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @Column(name = "usd_rate", precision = 20, scale = 8, nullable = false)
    private BigDecimal usdRate;

    /** One of IDENTITY | LIVE | MANUAL | PARTNER (DB CHECK constraint). */
    @Column(name = "source", length = 16, nullable = false)
    private String source;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    /** JPA only. */
    protected RateSnapshotEntity() {
    }

    public RateSnapshotEntity(String snapshotId, String currencyCode, BigDecimal usdRate,
                              String source, Instant effectiveAt, Instant capturedAt) {
        this.snapshotId = snapshotId;
        this.currencyCode = currencyCode;
        this.usdRate = usdRate;
        this.source = source;
        this.effectiveAt = effectiveAt;
        this.capturedAt = capturedAt;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public BigDecimal getUsdRate() {
        return usdRate;
    }

    public String getSource() {
        return source;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
