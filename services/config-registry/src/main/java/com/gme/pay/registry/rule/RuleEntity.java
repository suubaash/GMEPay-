package com.gme.pay.registry.rule;

import com.gme.pay.contracts.RuleView;
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
 * JPA-mapped row of the {@code partner_rule} table (V017) — one pricing rule
 * per (partner × scheme × direction), bitemporally versioned per ADR-010
 * (Slice 6 — Commercial Terms).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code PartnerEntity} (V004) and
 * {@code PrefundingConfigEntity} (V015): rows are NEVER UPDATEd in place. A
 * wizard step-6 save is a bulk replace — every current rule row of the
 * partner gets {@code superseded_at = now} and the new set is INSERTed with
 * {@code recorded_at = now}, both halves sharing one MICROS-truncated instant
 * (see {@link RuleService}).
 *
 * <p>The V017 column {@code current_rule_key} is the cross-engine
 * partial-unique emulation enforcing one CURRENT row per
 * (partner × scheme × direction): {@code 'partner_id:scheme_id:direction'} on
 * the current row, {@code NULL} on superseded rows, with a plain UNIQUE index
 * on it. It is APPLICATION-maintained (a stored GENERATED column has no
 * spelling both PG and H2 parse — PG requires {@code STORED}, H2 rejects it):
 * {@link #onPersist} stamps it on INSERT and {@link #onUpdate} clears it when
 * the row is superseded, so the invariant holds no matter which write path
 * flushes the entity.
 *
 * <h2>Margins / money</h2>
 *
 * <p>{@code mA} / {@code mB} are decimal FRACTIONS ({@code 0.0150} = 1.50%),
 * NUMERIC(7,4); {@code serviceChargeUsd} is major-USD-units money,
 * NUMERIC(19,4) per {@code docs/MONEY_CONVENTION.md}. The service normalises
 * all three to scale 4 before persisting so the stored value equals the
 * in-memory value on both PostgreSQL and H2 (the same stored-equals-in-memory
 * discipline the MICROS truncation gives the timestamps).
 *
 * <h2>Identifier</h2>
 *
 * <p>BIGSERIAL surrogate via {@link GenerationType#IDENTITY} (same strategy as
 * {@code PrefundingConfigEntity}): rows are minted fresh on every SCD-6 write
 * and nothing outside this package joins on their ids.
 */
@Entity
@Table(name = "partner_rule")
public class RuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** Scheme half of the rule key (V017); free-form until the Slice 7 registry. */
    @Column(name = "scheme_id", nullable = false, length = 40)
    private String schemeId;

    /** Direction half of the rule key (V017 CHECK roster: INBOUND / OUTBOUND / BOTH). */
    @Column(name = "direction", nullable = false, length = 10)
    private String direction;

    /** Partner-side margin as a decimal fraction (0.0150 = 1.50%); never NULL. */
    @Column(name = "m_a", nullable = false, precision = 7, scale = 4)
    private BigDecimal mA;

    /** GME-side margin as a decimal fraction; never NULL. */
    @Column(name = "m_b", nullable = false, precision = 7, scale = 4)
    private BigDecimal mB;

    /** Flat per-transaction service charge, major USD units; never NULL (defaults to 0). */
    @Column(name = "service_charge_usd", nullable = false, precision = 19, scale = 4)
    private BigDecimal serviceChargeUsd;

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

    /**
     * Partial-unique emulation key (V017): {@code partner_id:scheme_id:direction}
     * while this row is current, {@code NULL} once superseded. Maintained by
     * the lifecycle hooks below; the UNIQUE index {@code partner_rule_current}
     * enforces "one current row per key" on both engines.
     */
    @Column(name = "current_rule_key", length = 80)
    private String currentRuleKey;

    public RuleEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // MICROS truncation: the stored TIMESTAMP must equal the in-memory
            // value on both PostgreSQL and H2 — same discipline as
            // PartnerEntity.onPersist (Slice 1 lesson).
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = recordedAt;
        }
        if (serviceChargeUsd == null) {
            // Mirrors the V017 column DEFAULT 0 for entities built without one.
            serviceChargeUsd = BigDecimal.ZERO.setScale(4);
        }
        // Fresh rows are current by definition: stamp the partial-unique key
        // (superseded rows are never INSERTed — SCD-6 supersession is an UPDATE
        // of the prior row's superseded_at, handled by onUpdate below).
        if (supersededAt == null && currentRuleKey == null) {
            currentRuleKey = partnerId + ":" + schemeId + ":" + direction;
        }
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        // The only sanctioned UPDATE under SCD-6 is closing the transaction-time
        // interval; the partial-unique key must vacate the index slot so the
        // replacing row can claim it within the same transaction.
        if (supersededAt != null) {
            currentRuleKey = null;
        }
    }

    /** Adapt this row to the canonical {@link RuleView} wire DTO. */
    public RuleView toView() {
        return new RuleView(
                id,
                schemeId,
                direction,
                mA,
                mB,
                serviceChargeUsd,
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

    public BigDecimal getMA() {
        return mA;
    }

    public void setMA(BigDecimal mA) {
        this.mA = mA;
    }

    public BigDecimal getMB() {
        return mB;
    }

    public void setMB(BigDecimal mB) {
        this.mB = mB;
    }

    public BigDecimal getServiceChargeUsd() {
        return serviceChargeUsd;
    }

    public void setServiceChargeUsd(BigDecimal serviceChargeUsd) {
        this.serviceChargeUsd = serviceChargeUsd;
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
