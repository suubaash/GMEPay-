package com.gme.pay.registry.corridor;

import com.gme.pay.contracts.PartnerCorridorView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_corridor} table (V023) — one money
 * lane (src country/ccy → dst country/ccy) per partner, bitemporally
 * versioned per ADR-010 (Slice 7 — Schemes &amp; Corridors).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code PartnerEntity} (V004) and
 * {@code RuleEntity} (V017): rows are NEVER UPDATEd in place. A wizard step-7
 * save is a bulk replace — every current corridor row of the partner gets
 * {@code superseded_at = now} and the new set is INSERTed with
 * {@code recorded_at = now}, both halves sharing one MICROS-truncated instant
 * (see {@link PartnerCorridorService}).
 *
 * <p>Unlike V017's application-maintained {@code current_rule_key}, the V023
 * partial-unique emulation is fully DB-side: a stored GENERATED
 * {@code is_current} column (TRUE on current rows, NULL once superseded — the
 * V004 vendor-pair spelling, PG carries {@code STORED}, H2 omits it) closes a
 * composite UNIQUE index over the corridor key. The supersede UPDATE vacates
 * the index slot automatically, so this entity intentionally does NOT map the
 * column and carries no lifecycle bookkeeping for it.
 *
 * <h2>Identifier</h2>
 *
 * <p>{@code BIGINT GENERATED ALWAYS AS IDENTITY} surrogate via
 * {@link GenerationType#IDENTITY}: rows are minted fresh on every SCD-6 write
 * and nothing outside this package joins on their ids.
 */
@Entity
@Table(name = "partner_corridor")
public class PartnerCorridorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** Source country, ISO-3166 alpha-2 UPPERCASE (V023 CHAR(2)). */
    @Column(name = "src_country", nullable = false, length = 2)
    private String srcCountry;

    /** Source currency, ISO-4217 UPPERCASE (V023 CHAR(3)). */
    @Column(name = "src_ccy", nullable = false, length = 3)
    private String srcCcy;

    /** Destination country, ISO-3166 alpha-2 UPPERCASE (V023 CHAR(2)). */
    @Column(name = "dst_country", nullable = false, length = 2)
    private String dstCountry;

    /** Destination currency, ISO-4217 UPPERCASE (V023 CHAR(3)). */
    @Column(name = "dst_ccy", nullable = false, length = 3)
    private String dstCcy;

    /** When the corridor opens for live traffic; NULL = not yet scheduled. */
    @Column(name = "go_live_date")
    private LocalDate goLiveDate;

    /** Corridor toggle (gateway rejects traffic when false); never NULL (defaults to true). */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    /**
     * Per-corridor KoFIU STR-feed switch (V029.1, Slice 8 Lane C); never NULL
     * (defaults to false — STR is enabled lane-by-lane).
     */
    @Column(name = "str_enabled", nullable = false)
    private Boolean strEnabled;

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

    public PartnerCorridorEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // MICROS truncation: the stored TIMESTAMP must equal the in-memory
            // value on both PostgreSQL and H2 — same discipline as
            // PartnerEntity.onPersist / RuleEntity.onPersist (Slice 1 lesson).
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = recordedAt;
        }
        if (isActive == null) {
            // Mirrors the V023 column DEFAULT TRUE for entities built without one.
            isActive = Boolean.TRUE;
        }
        if (strEnabled == null) {
            // Mirrors the V029.1 column DEFAULT FALSE for entities built without one.
            strEnabled = Boolean.FALSE;
        }
    }

    /** Adapt this row to the canonical {@link PartnerCorridorView} wire DTO. */
    public PartnerCorridorView toView() {
        return new PartnerCorridorView(
                partnerId,
                srcCountry,
                srcCcy,
                dstCountry,
                dstCcy,
                goLiveDate,
                isActive,
                strEnabled);
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

    public String getSrcCountry() {
        return srcCountry;
    }

    public void setSrcCountry(String srcCountry) {
        this.srcCountry = srcCountry;
    }

    public String getSrcCcy() {
        return srcCcy;
    }

    public void setSrcCcy(String srcCcy) {
        this.srcCcy = srcCcy;
    }

    public String getDstCountry() {
        return dstCountry;
    }

    public void setDstCountry(String dstCountry) {
        this.dstCountry = dstCountry;
    }

    public String getDstCcy() {
        return dstCcy;
    }

    public void setDstCcy(String dstCcy) {
        this.dstCcy = dstCcy;
    }

    public LocalDate getGoLiveDate() {
        return goLiveDate;
    }

    public void setGoLiveDate(LocalDate goLiveDate) {
        this.goLiveDate = goLiveDate;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Boolean getStrEnabled() {
        return strEnabled;
    }

    public void setStrEnabled(Boolean strEnabled) {
        this.strEnabled = strEnabled;
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
