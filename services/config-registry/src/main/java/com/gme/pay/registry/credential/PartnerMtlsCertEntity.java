package com.gme.pay.registry.credential;

import com.gme.pay.contracts.PartnerMtlsCertView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_mtls_cert} table (V027) — one mTLS
 * client-certificate binding per (partner × environment × fingerprint),
 * bitemporally versioned per ADR-010 (Slice 8 Lane B — Credentials).
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code PartnerSchemeEntity} (V022): rows are
 * NEVER UPDATEd in place. A re-upload supersedes the prior current row for
 * the (partner, environment) and inserts the fresh one; a revoke supersedes
 * the ACTIVE row and inserts a REVOKED successor — both halves sharing one
 * MICROS-truncated instant (see {@link PartnerMtlsCertService}).
 *
 * <p>The V027 column {@code current_cert_key} is the cross-engine
 * partial-unique emulation enforcing one CURRENT row per
 * (partner × environment × fingerprint):
 * {@code 'partner_id:environment:fingerprint'} on the current row,
 * {@code NULL} on superseded rows, with a plain UNIQUE index. It is
 * APPLICATION-maintained (the V017/V022 pattern — a stored GENERATED column
 * has no spelling both PG and H2 parse): {@link #onPersist} stamps it on
 * INSERT and {@link #onUpdate} clears it when the row is superseded.
 */
@Entity
@Table(name = "partner_mtls_cert")
public class PartnerMtlsCertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    /** V027 CHECK roster: SANDBOX / PRODUCTION. */
    @Column(name = "environment", nullable = false, length = 20)
    private String environment;

    /** The uploaded leaf certificate, PEM-encoded, exactly as received. */
    @Column(name = "cert_pem", nullable = false, columnDefinition = "text")
    private String certPem;

    /** SHA-256 over the DER encoding, lowercase hex (64 chars). */
    @Column(name = "fingerprint_sha256", nullable = false, length = 64)
    private String fingerprintSha256;

    /** RFC 2253 subject DN, parsed at upload. */
    @Column(name = "subject_dn", length = 255)
    private String subjectDn;

    /** RFC 2253 issuer DN, parsed at upload. */
    @Column(name = "issuer_dn", length = 255)
    private String issuerDn;

    /** The X.509 artifact's own validity start (NOT the SCD-6 business time). */
    @Column(name = "not_before")
    private Instant notBefore;

    /** The X.509 artifact's own validity end. */
    @Column(name = "not_after")
    private Instant notAfter;

    /** V027 CHECK roster: ACTIVE / EXPIRED / REVOKED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

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
     * Partial-unique emulation key (V027):
     * {@code partner_id:environment:fingerprint} while this row is current,
     * {@code NULL} once superseded. Maintained by the lifecycle hooks below.
     */
    @Column(name = "current_cert_key", length = 120)
    private String currentCertKey;

    public PartnerMtlsCertEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // MICROS truncation — same discipline as PartnerSchemeEntity.onPersist.
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            validFrom = recordedAt;
        }
        // Fresh rows are current by definition: stamp the partial-unique key.
        if (supersededAt == null && currentCertKey == null) {
            currentCertKey = partnerId + ":" + environment + ":" + fingerprintSha256;
        }
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        // The only sanctioned UPDATE under SCD-6 is closing the transaction-time
        // interval; the partial-unique key must vacate the index slot so a
        // replacing row can claim it within the same transaction.
        if (supersededAt != null) {
            currentCertKey = null;
        }
    }

    /** Adapt this row to the canonical {@link PartnerMtlsCertView} wire DTO (no PEM). */
    public PartnerMtlsCertView toView() {
        return new PartnerMtlsCertView(
                id,
                environment,
                fingerprintSha256,
                subjectDn,
                issuerDn,
                notBefore,
                notAfter,
                status,
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

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getCertPem() {
        return certPem;
    }

    public void setCertPem(String certPem) {
        this.certPem = certPem;
    }

    public String getFingerprintSha256() {
        return fingerprintSha256;
    }

    public void setFingerprintSha256(String fingerprintSha256) {
        this.fingerprintSha256 = fingerprintSha256;
    }

    public String getSubjectDn() {
        return subjectDn;
    }

    public void setSubjectDn(String subjectDn) {
        this.subjectDn = subjectDn;
    }

    public String getIssuerDn() {
        return issuerDn;
    }

    public void setIssuerDn(String issuerDn) {
        this.issuerDn = issuerDn;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Instant notBefore) {
        this.notBefore = notBefore;
    }

    public Instant getNotAfter() {
        return notAfter;
    }

    public void setNotAfter(Instant notAfter) {
        this.notAfter = notAfter;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getCurrentCertKey() {
        return currentCertKey;
    }
}
