package com.gme.pay.registry.kyb;

import com.gme.pay.contracts.KybView;
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
 * JPA-mapped row of the {@code partner_kyb} table (V011) — the KYB
 * sub-resource of a partner, bitemporally versioned per ADR-010 (Slice 3).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code PartnerEntity} (V004) and
 * {@code ContactEntity} (V009): rows are NEVER UPDATEd in place. Wizard
 * step-3 saves and screening runs are paired writes — the current row gets
 * {@code superseded_at = now} and a fresh row is INSERTed with
 * {@code recorded_at = now}, both halves sharing one MICROS-truncated instant
 * (see {@link KybService}).
 *
 * <h2>UBO set</h2>
 *
 * <p>{@code ubo_set_jsonb} is TEXT carrying the canonical JSON array written
 * by {@link KybJson} — the same bytes that feed the ADR-007 audit hash, so
 * what the auditor sees IS what the row stores. Mapped as a plain
 * {@link String}; {@link KybJson#parseUbos} materialises it for views.
 *
 * <h2>Identifier</h2>
 *
 * <p>BIGSERIAL surrogate via {@link GenerationType#IDENTITY} (same strategy
 * as {@code ContactEntity}): KYB rows are minted fresh on every SCD-6 write
 * and nothing outside this package joins on their ids, so the
 * application-pulled-sequence pattern {@code PartnerStore} needs is
 * unnecessary here — Spring Data routes these through {@code em.persist()}
 * and {@code @PrePersist} fires on the entity itself.
 */
@Entity
@Table(name = "partner_kyb")
public class KybEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** LOW | MEDIUM | HIGH (V011 CHECK); NULL while the draft is incomplete. */
    @Column(name = "risk_rating", length = 10)
    private String riskRating;

    @Column(name = "risk_rationale", length = 1000)
    private String riskRationale;

    @Column(name = "next_review_date")
    private LocalDate nextReviewDate;

    @Column(name = "license_type", length = 50)
    private String licenseType;

    @Column(name = "license_number", length = 50)
    private String licenseNumber;

    @Column(name = "license_authority", length = 100)
    private String licenseAuthority;

    @Column(name = "license_expiry")
    private LocalDate licenseExpiry;

    /** Canonical JSON array of UBOs (TEXT — see V011 header); NULL = not captured. */
    @Column(name = "ubo_set_jsonb")
    private String uboSetJson;

    /** Wolfsberg CBDDQ document id in the ADR-006 vault; NULL until uploaded. */
    @Column(name = "cbddq_doc_id")
    private Long cbddqDocId;

    /** CLEAR | HIT | NEEDS_REVIEW (V011 CHECK); NULL before the first screening run. */
    @Column(name = "screening_status", length = 15)
    private String screeningStatus;

    @Column(name = "screening_provider_ref", length = 100)
    private String screeningProviderRef;

    @Column(name = "screened_at")
    private Instant screenedAt;

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

    public KybEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // MICROS truncation: the stored TIMESTAMP must equal the in-memory
            // value on both PostgreSQL and H2 — same discipline as
            // PartnerEntity / ContactEntity (Slice 1 lesson).
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            // KYB facts default to "true from when we recorded them" — the
            // wizard does not back-date (a future correction flow sets this
            // explicitly), same default as contacts.
            validFrom = recordedAt;
        }
    }

    /** Adapt this row to the canonical {@link KybView} wire DTO. */
    public KybView toView() {
        return new KybView(
                id,
                riskRating,
                riskRationale,
                nextReviewDate,
                licenseType,
                licenseNumber,
                licenseAuthority,
                licenseExpiry,
                KybJson.parseUbos(uboSetJson),
                cbddqDocId,
                screeningStatus,
                screeningProviderRef,
                screenedAt,
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

    public String getRiskRating() {
        return riskRating;
    }

    public void setRiskRating(String riskRating) {
        this.riskRating = riskRating;
    }

    public String getRiskRationale() {
        return riskRationale;
    }

    public void setRiskRationale(String riskRationale) {
        this.riskRationale = riskRationale;
    }

    public LocalDate getNextReviewDate() {
        return nextReviewDate;
    }

    public void setNextReviewDate(LocalDate nextReviewDate) {
        this.nextReviewDate = nextReviewDate;
    }

    public String getLicenseType() {
        return licenseType;
    }

    public void setLicenseType(String licenseType) {
        this.licenseType = licenseType;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }

    public void setLicenseNumber(String licenseNumber) {
        this.licenseNumber = licenseNumber;
    }

    public String getLicenseAuthority() {
        return licenseAuthority;
    }

    public void setLicenseAuthority(String licenseAuthority) {
        this.licenseAuthority = licenseAuthority;
    }

    public LocalDate getLicenseExpiry() {
        return licenseExpiry;
    }

    public void setLicenseExpiry(LocalDate licenseExpiry) {
        this.licenseExpiry = licenseExpiry;
    }

    public String getUboSetJson() {
        return uboSetJson;
    }

    public void setUboSetJson(String uboSetJson) {
        this.uboSetJson = uboSetJson;
    }

    public Long getCbddqDocId() {
        return cbddqDocId;
    }

    public void setCbddqDocId(Long cbddqDocId) {
        this.cbddqDocId = cbddqDocId;
    }

    public String getScreeningStatus() {
        return screeningStatus;
    }

    public void setScreeningStatus(String screeningStatus) {
        this.screeningStatus = screeningStatus;
    }

    public String getScreeningProviderRef() {
        return screeningProviderRef;
    }

    public void setScreeningProviderRef(String screeningProviderRef) {
        this.screeningProviderRef = screeningProviderRef;
    }

    public Instant getScreenedAt() {
        return screenedAt;
    }

    public void setScreenedAt(Instant screenedAt) {
        this.screenedAt = screenedAt;
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
