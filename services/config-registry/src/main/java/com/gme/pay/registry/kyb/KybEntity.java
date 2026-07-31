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

    /**
     * CLEAR | CLEAR_MANUAL_ATTESTATION | HIT | NEEDS_REVIEW |
     * NOT_SCREENED_NO_PROVIDER (V045 CHECK); NULL before the first screening run.
     *
     * <p>T1-4: {@code CLEAR} is reachable only alongside
     * {@link #screeningAuthoritative} TRUE — enforced by lib-kyb's
     * {@code ScreeningResult} on the write path and by the V042 CHECK
     * {@code ck_partner_kyb_clear_requires_authority} in the database.
     *
     * <p>T1-4 (owner decision): {@code CLEAR_MANUAL_ATTESTATION} is the clean verdict
     * of a MANUAL screening a named human performed under a compliance-signed SOP.
     * It is a distinct value from {@code CLEAR} so the two authorities stay
     * distinguishable at every hop that carries only this string, and it is
     * reachable only alongside a COMPLETE attestation (V045
     * {@code ck_partner_kyb_manual_clear_requires_attestation}).
     */
    @Column(name = "screening_status", length = 32)
    private String screeningStatus;

    @Column(name = "screening_provider_ref", length = 100)
    private String screeningProviderRef;

    @Column(name = "screened_at")
    private Instant screenedAt;

    /** V042: producing provider id — {@code stub} / {@code unknown} / a vendor id. */
    @Column(name = "screening_provider_id", length = 32)
    private String screeningProviderId;

    /**
     * V042: TRUE only when a real screening provider consulted sanctions / PEP /
     * adverse-media sources. NULL on rows that never screened. Anything other
     * than TRUE means <b>the partner has not been screened</b>, and
     * {@code ActivationGateService} refuses the sanctions pre-condition.
     */
    @Column(name = "screening_authoritative")
    private Boolean screeningAuthoritative;

    /** V042: why the run is not authoritative; NULL on an authoritative run. */
    @Column(name = "screening_caveat", length = 512)
    private String screeningCaveat;

    /**
     * V045 (T1-4 manual KYB SOP): the verified human who performed the manual
     * screening, in the T5-1 {@code AuditActors} vocabulary — the SAME value as the
     * {@code actor_id} of the accompanying {@code PARTNER_KYB_MANUAL_SCREENING_ATTESTED}
     * audit row. NULL on every non-manual run.
     */
    @Column(name = "manual_attester_actor_id", length = 64)
    private String manualAttesterActorId;

    /** V045: when the attestation was made (MICROS-truncated). NULL on a non-manual run. */
    @Column(name = "manual_attested_at")
    private Instant manualAttestedAt;

    /** V045: the compliance-signed SOP document the attester followed. */
    @Column(name = "manual_sop_document_ref", length = 128)
    private String manualSopDocumentRef;

    /** V045: the revision of that SOP document. */
    @Column(name = "manual_sop_version", length = 32)
    private String manualSopVersion;

    /** V045: which lists / registers / sources were consulted, in the attester's own words. */
    @Column(name = "manual_sources_consulted", length = 2000)
    private String manualSourcesConsulted;

    /**
     * Wave-3 (V036): the collapsed KYB-verify verdict from kyb-adapter
     * ({@code APPROVED}/{@code MANUAL_REVIEW}/{@code REJECTED}); NULL before any
     * verify run. Opaque String — the decision roster is owned by kyb-adapter.
     */
    @Column(name = "verification_decision", length = 20)
    private String verificationDecision;

    /** Wave-3 (V036): one-line explanation accompanying {@link #verificationDecision}; NULL when unset. */
    @Column(name = "verification_decision_reason", length = 500)
    private String verificationDecisionReason;

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

    public String getScreeningProviderId() {
        return screeningProviderId;
    }

    public void setScreeningProviderId(String screeningProviderId) {
        this.screeningProviderId = screeningProviderId;
    }

    public Boolean getScreeningAuthoritative() {
        return screeningAuthoritative;
    }

    public void setScreeningAuthoritative(Boolean screeningAuthoritative) {
        this.screeningAuthoritative = screeningAuthoritative;
    }

    public String getScreeningCaveat() {
        return screeningCaveat;
    }

    public void setScreeningCaveat(String screeningCaveat) {
        this.screeningCaveat = screeningCaveat;
    }

    public String getManualAttesterActorId() {
        return manualAttesterActorId;
    }

    public void setManualAttesterActorId(String manualAttesterActorId) {
        this.manualAttesterActorId = manualAttesterActorId;
    }

    public Instant getManualAttestedAt() {
        return manualAttestedAt;
    }

    public void setManualAttestedAt(Instant manualAttestedAt) {
        this.manualAttestedAt = manualAttestedAt;
    }

    public String getManualSopDocumentRef() {
        return manualSopDocumentRef;
    }

    public void setManualSopDocumentRef(String manualSopDocumentRef) {
        this.manualSopDocumentRef = manualSopDocumentRef;
    }

    public String getManualSopVersion() {
        return manualSopVersion;
    }

    public void setManualSopVersion(String manualSopVersion) {
        this.manualSopVersion = manualSopVersion;
    }

    public String getManualSourcesConsulted() {
        return manualSourcesConsulted;
    }

    public void setManualSourcesConsulted(String manualSourcesConsulted) {
        this.manualSourcesConsulted = manualSourcesConsulted;
    }

    /**
     * {@code true} only when this row's screening verdict came from a provider
     * that actually screened (T1-4). The single predicate the activation gate and
     * every reader should use — never {@code "CLEAR".equals(screeningStatus)}
     * alone, and never merely "not HIT".
     *
     * <p>True for both authorities: a vendor screening and an attested manual one.
     * Callers that must PRESENT the two differently use
     * {@link #isManuallyAttestedScreening()}; callers deciding whether a screening
     * happened at all use this.
     */
    public boolean hasAuthoritativeScreening() {
        return Boolean.TRUE.equals(screeningAuthoritative)
                && screeningStatus != null
                && !SCREENING_NOT_PERFORMED.equals(screeningStatus);
    }

    /** The honest status a non-authoritative provider records instead of CLEAR (lib-kyb roster). */
    public static final String SCREENING_NOT_PERFORMED = "NOT_SCREENED_NO_PROVIDER";

    /**
     * The clean verdict of an attested MANUAL screening (V045, T1-4 owner decision).
     * Never collapsed into {@link #SCREENING_CLEAR}: downstream must be able to tell a
     * vendor screening from a human one.
     */
    public static final String SCREENING_CLEAR_MANUAL_ATTESTATION = "CLEAR_MANUAL_ATTESTATION";

    /** The clean verdict of a real vendor screening. */
    public static final String SCREENING_CLEAR = "CLEAR";

    /** Provider id of an attested manual screening (mirrors lib-kyb's ScreeningProvenance). */
    public static final String MANUAL_ATTESTATION_PROVIDER_ID = "manual-sop";

    /**
     * {@code true} when this row's verdict rests on a MANUAL screening rather than a
     * vendor — i.e. the provenance names the manual-SOP authority. Says nothing about
     * whether the attestation behind it is complete; see
     * {@link #hasCompleteManualAttestation()}.
     */
    public boolean isManuallyAttestedScreening() {
        return MANUAL_ATTESTATION_PROVIDER_ID.equals(screeningProviderId)
                || SCREENING_CLEAR_MANUAL_ATTESTATION.equals(screeningStatus);
    }

    /**
     * {@code true} when every field a manual attestation must carry is present: who
     * attested, when, which SOP document, which version of it, and what was checked.
     *
     * <p>The activation gate tests this separately from
     * {@link #hasAuthoritativeScreening()} on purpose. The V045 CHECKs make an
     * incomplete attestation unrepresentable at the database, but a gate that trusted
     * the constraint would report "screened" for a row written before V045, restored
     * from an older dump, or produced by a future migration — and the failure mode of
     * trusting it is a partner going LIVE on an attestation that names nobody.
     */
    public boolean hasCompleteManualAttestation() {
        return notBlank(manualAttesterActorId)
                && manualAttestedAt != null
                && notBlank(manualSopDocumentRef)
                && notBlank(manualSopVersion)
                && notBlank(manualSourcesConsulted);
    }

    /**
     * {@code true} when the stored screening reports no matches — under either
     * authority. The activation gate's sanctions-clearance check keys off this so a
     * manual clean verdict is honoured without {@code CLEAR_MANUAL_ATTESTATION} having
     * to be string-matched at every call site.
     */
    public boolean screeningIsClear() {
        return SCREENING_CLEAR.equals(screeningStatus)
                || SCREENING_CLEAR_MANUAL_ATTESTATION.equals(screeningStatus);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public String getVerificationDecision() {
        return verificationDecision;
    }

    public void setVerificationDecision(String verificationDecision) {
        this.verificationDecision = verificationDecision;
    }

    public String getVerificationDecisionReason() {
        return verificationDecisionReason;
    }

    public void setVerificationDecisionReason(String verificationDecisionReason) {
        this.verificationDecisionReason = verificationDecisionReason;
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
