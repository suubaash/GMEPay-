package com.gme.pay.registry.scheme;

import com.gme.pay.contracts.SchemeCommissionShareView;
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
 * JPA-mapped row of the {@code scheme_commission_share} table (V031) — one
 * (scheme × direction) commission-share row, bitemporally versioned per
 * ADR-010.
 *
 * <p>Models the configurable GME ↔ scheme split of the NET merchant fee. There
 * is <b>no fixed 70/30</b>: {@link #gmeSharePct} is GME's fraction of the net
 * fee and the scheme keeps the remainder; {@link #vanFeePct} is the VAN
 * intermediary rate deducted from the GROSS merchant fee before the split.
 * Consumed by the revenue split engine
 * ({@code SchemeFeeSplitCalculator.gmeFeeSharePct} / {@code vanFeeRate}).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code FeeScheduleEntity} (V018): rows are NEVER
 * UPDATEd in place. A save is a BULK REPLACE — every current row of the scheme
 * gets {@code superseded_at = now} and the fresh set is INSERTed with
 * {@code recorded_at = now}, both halves sharing one MICROS-truncated instant
 * (see {@code SchemeCommissionShareService}).
 */
@Entity
@Table(name = "scheme_commission_share")
public class SchemeCommissionShareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** Scheme CODE this row prices (e.g. {@code "ZEROPAY"}); never NULL. */
    @Column(name = "scheme_id", length = 40, nullable = false, updatable = false)
    private String schemeId;

    /** Direction (V031 CHECK roster: INBOUND / OUTBOUND / BOTH); NULL = all. */
    @Column(name = "direction", length = 10)
    private String direction;

    /** GME's fraction of the net merchant fee, NUMERIC(6,4) in (0,1]; never NULL. */
    @Column(name = "gme_share_pct", nullable = false, precision = 6, scale = 4)
    private BigDecimal gmeSharePct;

    /** VAN intermediary rate deducted before the split, NUMERIC(7,4); never NULL (DB DEFAULT 0). */
    @Column(name = "van_fee_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal vanFeePct;

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

    public SchemeCommissionShareEntity() {
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

    /** Adapt this row to the canonical {@link SchemeCommissionShareView} wire DTO. */
    public SchemeCommissionShareView toView() {
        return new SchemeCommissionShareView(
                id,
                schemeId,
                direction,
                gmeSharePct,
                vanFeePct,
                validFrom,
                validTo,
                recordedAt);
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

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public BigDecimal getGmeSharePct() {
        return gmeSharePct;
    }

    public void setGmeSharePct(BigDecimal gmeSharePct) {
        this.gmeSharePct = gmeSharePct;
    }

    public BigDecimal getVanFeePct() {
        return vanFeePct;
    }

    public void setVanFeePct(BigDecimal vanFeePct) {
        this.vanFeePct = vanFeePct;
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
