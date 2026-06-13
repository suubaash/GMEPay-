package com.gme.pay.registry.commercial;

import com.gme.pay.contracts.FeeScheduleView;
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
 * JPA-mapped row of the {@code partner_fee_schedule} table (V018) — one
 * (partner × scheme × direction) fee row, bitemporally versioned per ADR-010
 * (Slice 6 — Commercial Terms).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code BankAccountEntity} (V012): rows are NEVER
 * UPDATEd in place. A wizard step-6 save is a BULK REPLACE — every current row
 * of the partner gets {@code superseded_at = now} and the fresh set is
 * INSERTed with {@code recorded_at = now}, both halves sharing one
 * MICROS-truncated instant (see {@link FeeScheduleService}).
 *
 * <h2>Tier table</h2>
 *
 * <p>{@code tier_table_json} is TEXT carrying the canonical JSON array written
 * by {@link FeeTierTableJson} — same TEXT-not-JSONB choice as V011's
 * {@code ubo_set_jsonb} (H2 compat) and same canonical-bytes discipline (the
 * stored string participates in the ADR-007 audit snapshot, so it must be
 * deterministic).
 *
 * <h2>Identifier</h2>
 *
 * <p>BIGSERIAL surrogate via {@link GenerationType#IDENTITY} (same strategy as
 * {@code BankAccountEntity}): rows are minted fresh on every SCD-6 bulk
 * replace and nothing outside this package joins on their ids.
 */
@Entity
@Table(name = "partner_fee_schedule")
public class FeeScheduleEntity {

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

    /** Direction (V018 CHECK roster: INBOUND / OUTBOUND / BOTH); NULL = all. */
    @Column(name = "direction", length = 10)
    private String direction;

    /** Flat per-transaction fee, major USD units; never NULL (DB DEFAULT 0). */
    @Column(name = "fixed_fee_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal fixedFeeUsd;

    /** Variable fee in basis points (NUMERIC(7,4)); never NULL (DB DEFAULT 0). */
    @Column(name = "bps_fee", nullable = false, precision = 7, scale = 4)
    private BigDecimal bpsFee;

    /** Canonical JSON array of {fromVolumeUsd, bpsOverride}; NULL = no tiers. */
    @Column(name = "tier_table_json")
    private String tierTableJson;

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

    public FeeScheduleEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // MICROS truncation: stored TIMESTAMP must equal the in-memory
            // value on both PostgreSQL and H2 (Slice 1 lesson).
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = recordedAt;
        }
    }

    /** Adapt this row to the canonical {@link FeeScheduleView} wire DTO. */
    public FeeScheduleView toView() {
        return new FeeScheduleView(
                id,
                schemeId,
                direction,
                fixedFeeUsd,
                bpsFee,
                FeeTierTableJson.parse(tierTableJson),
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

    public BigDecimal getFixedFeeUsd() {
        return fixedFeeUsd;
    }

    public void setFixedFeeUsd(BigDecimal fixedFeeUsd) {
        this.fixedFeeUsd = fixedFeeUsd;
    }

    public BigDecimal getBpsFee() {
        return bpsFee;
    }

    public void setBpsFee(BigDecimal bpsFee) {
        this.bpsFee = bpsFee;
    }

    public String getTierTableJson() {
        return tierTableJson;
    }

    public void setTierTableJson(String tierTableJson) {
        this.tierTableJson = tierTableJson;
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
