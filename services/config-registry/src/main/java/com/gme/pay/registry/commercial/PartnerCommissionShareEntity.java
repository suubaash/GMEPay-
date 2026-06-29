package com.gme.pay.registry.commercial;

import com.gme.pay.contracts.PartnerCommissionShareView;
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
 * JPA-mapped row of the {@code partner_commission_share} table (V031) — one
 * (partner × scheme × direction) commission-share row, bitemporally versioned
 * per ADR-010.
 *
 * <p>Models the configurable GME ↔ partner split of GME's commission (its cut
 * of the net merchant fee, after the scheme split): {@link #partnerSharePct} is
 * the partner's fraction and GME keeps the remainder. There is <b>no fixed
 * share</b>.
 *
 * <p>Same SCD-6 discipline as {@link FeeScheduleEntity} (V018): rows are NEVER
 * UPDATEd in place — a save is a BULK REPLACE sharing one MICROS-truncated
 * instant (see {@code PartnerCommissionShareService}).
 */
@Entity
@Table(name = "partner_commission_share")
public class PartnerCommissionShareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** Scheme this row prices; NULL = all schemes (partner-wide default). */
    @Column(name = "scheme_id", length = 40)
    private String schemeId;

    /** Direction (V031 CHECK roster: INBOUND / OUTBOUND / BOTH); NULL = all. */
    @Column(name = "direction", length = 10)
    private String direction;

    /** Partner's fraction of GME's commission, NUMERIC(6,4) in [0,1]; never NULL. */
    @Column(name = "partner_share_pct", nullable = false, precision = 6, scale = 4)
    private BigDecimal partnerSharePct;

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

    public PartnerCommissionShareEntity() {
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

    /** Adapt this row to the canonical {@link PartnerCommissionShareView} wire DTO. */
    public PartnerCommissionShareView toView() {
        return new PartnerCommissionShareView(
                id,
                schemeId,
                direction,
                partnerSharePct,
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

    public String getSchemeId() {
        return schemeId;
    }

    public void setSchemeId(String schemeId) {
        this.schemeId = schemeId;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public BigDecimal getPartnerSharePct() {
        return partnerSharePct;
    }

    public void setPartnerSharePct(BigDecimal partnerSharePct) {
        this.partnerSharePct = partnerSharePct;
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
