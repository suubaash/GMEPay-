package com.gme.pay.registry.scheme;

import com.gme.pay.contracts.MerchantFeeScheduleView;
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
 * JPA-mapped row of the {@code merchant_fee_schedule} table (V032) — one
 * (scheme × merchant type) gross-fee row, bitemporally versioned per ADR-010.
 *
 * <p>The gross merchant fee rate that feeds the V031 commission split
 * (gross = payout × {@link #merchantFeePct}). {@code merchantType} NULL = the
 * scheme's default rate. Same SCD-6 bulk-replace discipline as
 * {@code SchemeCommissionShareEntity}.
 */
@Entity
@Table(name = "merchant_fee_schedule")
public class MerchantFeeScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** Scheme CODE this row prices (e.g. {@code "ZEROPAY"}); never NULL. */
    @Column(name = "scheme_id", length = 40, nullable = false, updatable = false)
    private String schemeId;

    /** Merchant category; NULL = the scheme's default rate. */
    @Column(name = "merchant_type", length = 40)
    private String merchantType;

    /** Gross merchant fee rate, NUMERIC(7,4) in [0,1]; never NULL. */
    @Column(name = "merchant_fee_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal merchantFeePct;

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

    public MerchantFeeScheduleEntity() {
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

    /** Adapt this row to the canonical {@link MerchantFeeScheduleView} wire DTO. */
    public MerchantFeeScheduleView toView() {
        return new MerchantFeeScheduleView(
                id, schemeId, merchantType, merchantFeePct, validFrom, validTo, recordedAt);
    }

    public Long getId() {
        return id;
    }

    public String getSchemeId() {
        return schemeId;
    }

    public void setSchemeId(String schemeId) {
        this.schemeId = schemeId;
    }

    public String getMerchantType() {
        return merchantType;
    }

    public void setMerchantType(String merchantType) {
        this.merchantType = merchantType;
    }

    public BigDecimal getMerchantFeePct() {
        return merchantFeePct;
    }

    public void setMerchantFeePct(BigDecimal merchantFeePct) {
        this.merchantFeePct = merchantFeePct;
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
