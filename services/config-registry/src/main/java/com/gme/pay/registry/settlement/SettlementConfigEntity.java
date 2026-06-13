package com.gme.pay.registry.settlement;

import com.gme.pay.contracts.SettlementConfigView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_settlement_config} table (V013) — the
 * settlement parameters of a partner, bitemporally versioned per ADR-010
 * (Slice 4).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code PartnerEntity} (V004) and
 * {@code KybEntity} (V011): rows are NEVER UPDATEd in place. A wizard step-4
 * settlement save is a paired write — the current row gets
 * {@code superseded_at = now} and a fresh row is INSERTed with
 * {@code recorded_at = now}, both halves sharing one MICROS-truncated instant
 * (see {@link SettlementConfigService}).
 *
 * <h2>Cutoff representation</h2>
 *
 * <p>The cutoff is a wall-clock {@link LocalTime} plus an IANA zone id string
 * — the same shape treasury states it in ("16:30 Asia/Seoul") and portable
 * across PostgreSQL and H2. {@link SettlementScheduleCalculator} resolves the
 * pair against a transaction instant at projection time.
 *
 * <h2>Identifier</h2>
 *
 * <p>BIGSERIAL surrogate via {@link GenerationType#IDENTITY} (same strategy as
 * {@code KybEntity}): rows are minted fresh on every SCD-6 write and nothing
 * outside this package joins on their ids, so Spring Data routes these through
 * {@code em.persist()} and {@code @PrePersist} fires on the entity itself.
 */
@Entity
@Table(name = "partner_settlement_config")
public class SettlementConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** Settlement cycle in BUSINESS days after the value date (0..5, V013 CHECK). */
    @Column(name = "cycle_t_plus_n", nullable = false)
    private Integer cycleTPlusN;

    /** Daily cutoff, wall-clock time-of-day evaluated in {@link #cutoffTimezone}. */
    @Column(name = "cutoff_time", nullable = false)
    private LocalTime cutoffTime;

    /** IANA zone id the cutoff is evaluated in (validated via ZoneId.of server-side). */
    @Column(name = "cutoff_timezone", nullable = false, length = 40)
    private String cutoffTimezone;

    /** Payout rail (V013 CHECK roster); never NULL. */
    @Column(name = "settlement_method", nullable = false, length = 20)
    private String settlementMethod;

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

    public SettlementConfigEntity() {
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
    }

    /** Adapt this row to the canonical {@link SettlementConfigView} wire DTO. */
    public SettlementConfigView toView() {
        return new SettlementConfigView(
                id,
                cycleTPlusN,
                cutoffTime,
                cutoffTimezone,
                settlementMethod,
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

    public Integer getCycleTPlusN() {
        return cycleTPlusN;
    }

    public void setCycleTPlusN(Integer cycleTPlusN) {
        this.cycleTPlusN = cycleTPlusN;
    }

    public LocalTime getCutoffTime() {
        return cutoffTime;
    }

    public void setCutoffTime(LocalTime cutoffTime) {
        this.cutoffTime = cutoffTime;
    }

    public String getCutoffTimezone() {
        return cutoffTimezone;
    }

    public void setCutoffTimezone(String cutoffTimezone) {
        this.cutoffTimezone = cutoffTimezone;
    }

    public String getSettlementMethod() {
        return settlementMethod;
    }

    public void setSettlementMethod(String settlementMethod) {
        this.settlementMethod = settlementMethod;
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
