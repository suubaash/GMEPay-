package com.gme.pay.registry.document;

import com.gme.pay.contracts.DocumentView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * JPA-mapped row of the {@code partner_document} table (V010) — metadata of one
 * stored KYB document version, bitemporally versioned per ADR-010 (Slice 3).
 * The BYTES live in the ADR-006 vault at {@code vault_uri}; this row records
 * what/when/digest.
 *
 * <h2>Bitemporal storage</h2>
 *
 * <p>Same SCD-6 discipline as {@code ContactEntity} (V009): rows are NEVER
 * UPDATEd in place. A re-upload of the same {@code (partner, docType)}
 * supersedes the prior current row ({@code superseded_at = now}) and INSERTs a
 * fresh row with {@code version = n+1}, both halves sharing one
 * MICROS-truncated instant (see {@link PartnerDocumentService#upload}).
 * Superseded rows keep their {@code vault_uri} pointing at the immutable prior
 * object — that is the version history the document viewer walks.
 *
 * <h2>Identifier</h2>
 *
 * <p>Engine-managed BIGSERIAL via {@link GenerationType#IDENTITY}, same as
 * {@code ContactEntity}: document rows are minted fresh per upload and nothing
 * joins on them across services, so Spring Data routes through
 * {@code em.persist()} and {@code @PrePersist} fires on the entity itself (no
 * manually-assigned-id {@code em.merge()} pitfall).
 */
@Entity
@Table(name = "partner_document")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** FK to {@code partners.id} (the V003/V004 BIGINT surrogate). */
    @Column(name = "partner_id", nullable = false, updatable = false)
    private Long partnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30)
    private DocumentType docType;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /** ADR-006 vault locator of the immutable stored object. */
    @Column(name = "vault_uri", nullable = false, length = 500)
    private String vaultUri;

    /** 1-based version per (partner, docType) — the {@code v<n>} path segment. */
    @Column(name = "version", nullable = false)
    private int version;

    /** Lowercase hex SHA-256 of the stored bytes. */
    @Column(name = "sha256", length = 64)
    private String sha256;

    /** Expiry printed on the document itself; NULL = non-expiring. */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** Operator who verified the scan (4-eyes); NULL until verification lands. */
    @Column(name = "verified_by", length = 64)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

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

    public DocumentEntity() {
        // JPA
    }

    @jakarta.persistence.PrePersist
    void onPersist() {
        if (recordedAt == null) {
            // MICROS truncation so the stored TIMESTAMP equals the in-memory
            // Instant on both PostgreSQL and H2 — same Slice 1 lesson as
            // PartnerEntity/ContactEntity.onPersist.
            recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (validFrom == null) {
            // A document fact has no meaningful existence before we stored it.
            validFrom = recordedAt;
        }
    }

    /** Adapt this row to the canonical {@link DocumentView} wire DTO. */
    public DocumentView toView() {
        return new DocumentView(
                id,
                docType == null ? null : docType.name(),
                filename,
                contentType,
                vaultUri,
                version,
                sha256,
                expiryDate,
                verifiedBy,
                verifiedAt,
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

    public DocumentType getDocType() {
        return docType;
    }

    public void setDocType(DocumentType docType) {
        this.docType = docType;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getVaultUri() {
        return vaultUri;
    }

    public void setVaultUri(String vaultUri) {
        this.vaultUri = vaultUri;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(String verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
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
