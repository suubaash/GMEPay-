package com.gme.pay.registry.commercial;

import com.gme.pay.contracts.FxConfigView;
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
 * JPA-mapped row of the {@code partner_fx_config} table (V019) — the FX
 * margin / reference-rate parameters of a partner, bitemporally versioned per
 * ADR-010 (Slice 6 — Commercial Terms).
 *
 * <p>Same SCD-6 paired-write discipline as {@code PrefundingConfigEntity}
 * (V015): one current row per partner, never UPDATEd in place — see
 * {@link FxConfigService}. BIGSERIAL surrogate via
 * {@link GenerationType#IDENTITY}, same strategy as its V013/V015 siblings.
 */
@Entity
@Table(name = "partner_fx_config")
public class FxConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** FX margin in basis points (NUMERIC(7,4)); never NULL (DB DEFAULT 0). */
    @Column(name = "margin_bps", nullable = false, precision = 7, scale = 4)
    private BigDecimal marginBps;

    /** Reference-rate source (V019 CHECK roster); never NULL. */
    @Column(name = "reference_rate_source", nullable = false, length = 30)
    private String referenceRateSource;

    /** Quote hold in seconds, 60..1800 (V019 CHECK); never NULL (DB DEFAULT 300). */
    @Column(name = "quote_hold_seconds", nullable = false)
    private Integer quoteHoldSeconds;

    /**
     * Step 10 (V033): whether the partner's FX margin is disclosed (transparency flag). Never NULL
     * (DB DEFAULT false). Recorded for reporting/compliance — does not affect pricing.
     */
    @Column(name = "disclosed_partner_margin", nullable = false)
    private Boolean disclosedPartnerMargin;

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

    public FxConfigEntity() {
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
        if (disclosedPartnerMargin == null) {
            disclosedPartnerMargin = Boolean.FALSE;
        }
    }

    /** Adapt this row to the canonical {@link FxConfigView} wire DTO. */
    public FxConfigView toView() {
        return new FxConfigView(
                id,
                marginBps,
                referenceRateSource,
                quoteHoldSeconds,
                disclosedPartnerMargin,
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

    public BigDecimal getMarginBps() {
        return marginBps;
    }

    public void setMarginBps(BigDecimal marginBps) {
        this.marginBps = marginBps;
    }

    public String getReferenceRateSource() {
        return referenceRateSource;
    }

    public void setReferenceRateSource(String referenceRateSource) {
        this.referenceRateSource = referenceRateSource;
    }

    public Integer getQuoteHoldSeconds() {
        return quoteHoldSeconds;
    }

    public void setQuoteHoldSeconds(Integer quoteHoldSeconds) {
        this.quoteHoldSeconds = quoteHoldSeconds;
    }

    public Boolean getDisclosedPartnerMargin() {
        return disclosedPartnerMargin;
    }

    public void setDisclosedPartnerMargin(Boolean disclosedPartnerMargin) {
        this.disclosedPartnerMargin = disclosedPartnerMargin;
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
