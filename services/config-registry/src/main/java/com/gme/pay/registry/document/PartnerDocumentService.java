package com.gme.pay.registry.document;

import com.gme.pay.contracts.DocumentView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.vault.VaultClient;
import com.gme.pay.vault.VaultException;
import com.gme.pay.vault.VaultObject;
import com.gme.pay.vault.VaultObjectRef;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 3 (3A.1) — owns the {@code partner_document} child aggregate (V010)
 * behind the wizard's step-3 document endpoints. The bytes go to the ADR-006
 * vault through the {@link VaultClient} port; this service records the
 * metadata row and the ADR-007 audit event.
 *
 * <h2>Upload semantics</h2>
 *
 * <p>An upload of doc type T is "the new current T": inside one transaction the
 * prior current row for {@code (partner, T)} — if any — is superseded
 * ({@code superseded_at = now}) and a fresh row is inserted
 * ({@code recorded_at = now}), both halves sharing one MICROS-truncated instant
 * (the SCD-6 paired-write discipline of {@code PartnerStore.save} /
 * {@code PartnerContactService}). The vault object of the superseded row is
 * NOT touched — object-lock makes it immutable, and the superseded row's
 * {@code vault_uri} is exactly what the document viewer's version history
 * walks.
 *
 * <h2>Vault-write ordering</h2>
 *
 * <p>The byte stream goes to the vault BEFORE any row is written: the vault is
 * not a transactional resource, so the failure mode must be "object stored,
 * row missing" (a harmless orphan version in an immutable bucket — the version
 * counter just moves on) and never "row present, object missing" (a metadata
 * row pointing at nothing, visible to operators). A DB rollback after a
 * successful vault PUT therefore leaks at most one unreferenced object.
 *
 * <h2>Write gating</h2>
 *
 * <p>Uploads are only permitted while the partner is in {@code ONBOARDING} —
 * the same draft-immutability rule as step-2 contacts (409 otherwise).
 * Post-activation document renewals go through the change_request 4-eyes path
 * in a later slice. Reads (list / metadata / download) work in any status:
 * examiners read documents of LIVE partners.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per upload, {@code aggregateType="partner_document"}, keyed
 * by the partner business code, BEFORE = canonical JSON of the superseded row
 * ({@code null} for a first upload of the type), AFTER = canonical JSON of the
 * fresh row (carrying {@code sha256} — the digest is thereby hash-chained).
 * Resolved through an {@link ObjectProvider} so {@code @DataJpaTest} slices
 * that omit the audit module skip publication silently, same wiring contract
 * as {@code PartnerStore} / {@code PartnerContactService}.
 */
@Service
public class PartnerDocumentService {

    /** Aggregate-type discriminator on audit rows for document mutations. */
    public static final String AGGREGATE_TYPE = "partner_document";

    /** Audit verb for a document upload (first or re-upload). */
    public static final String EVENT_TYPE = "PARTNER_DOCUMENT_UPLOADED";

    /**
     * Default actor until the Keycloak {@code sub} claim is threaded through
     * the BFF (same Slice 1B.4 carve-out as {@code PartnerDraftService}).
     */
    private static final String DEFAULT_ACTOR = "system";

    private final DocumentRepository documentRepository;
    private final PartnerRepository partnerRepository;
    private final VaultClient vaultClient;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerDocumentService(DocumentRepository documentRepository,
                                  PartnerRepository partnerRepository,
                                  VaultClient vaultClient,
                                  ObjectProvider<AuditLogService> auditLogProvider) {
        this.documentRepository = documentRepository;
        this.partnerRepository = partnerRepository;
        this.vaultClient = vaultClient;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Store one document: stream to the vault, record the metadata row,
     * publish the audit event.
     *
     * @param partnerCode the human-facing business code routing the POST.
     * @param docTypeRaw  document type token; must be on the {@link DocumentType}
     *                    roster (400 otherwise).
     * @param expiryDate  expiry printed on the document, or {@code null}.
     * @param filename    original upload filename (required).
     * @param contentType MIME type; {@code null}/blank falls back to
     *                    {@code application/octet-stream}.
     * @param content     the bytes; fully consumed, not closed.
     * @param actor       the operator (X-Actor header); {@code "system"} when absent.
     * @return the fresh current row as canonical {@link DocumentView}.
     * @throws ResponseStatusException 404 unknown partner; 409 partner not in
     *         {@code ONBOARDING}; 400 validation failure; 502 vault unreachable.
     */
    @Transactional
    public DocumentView upload(String partnerCode, String docTypeRaw, LocalDate expiryDate,
                               String filename, String contentType, InputStream content,
                               String actor) {
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", document uploads are only permitted while ONBOARDING");
        }
        DocumentType docType = parseDocType(docTypeRaw);
        if (filename == null || filename.isBlank()) {
            throw badRequest("filename is required");
        }
        if (filename.length() > 255) {
            throw badRequest("filename must be at most 255 characters");
        }
        String resolvedContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        if (resolvedContentType.length() > 100) {
            throw badRequest("contentType must be at most 100 characters");
        }

        // Vault first (see class javadoc, "Vault-write ordering"). A vault
        // failure surfaces as 502 — the registry row count stays untouched.
        VaultObjectRef ref;
        try {
            ref = vaultClient.store(partnerCode, docType.name(), filename,
                    resolvedContentType, content);
        } catch (VaultException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "document vault rejected the upload: " + e.getMessage(), e);
        }

        // One transaction-time instant shared by both halves of the paired
        // write, truncated to MICROS — see PartnerStore.save.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        DocumentEntity prior = documentRepository
                .findCurrentByPartnerIdAndDocType(partner.getId(), docType)
                .orElse(null);
        byte[] before = prior == null ? null : DocumentJson.canonical(prior);
        if (prior != null) {
            // Close out, then open — same write-order discipline as
            // PartnerContactService.replaceDraftContacts.
            prior.setSupersededAt(now);
            documentRepository.saveAndFlush(prior);
        }

        DocumentEntity fresh = new DocumentEntity();
        fresh.setPartnerId(partner.getId());
        fresh.setDocType(docType);
        fresh.setFilename(filename);
        fresh.setContentType(resolvedContentType);
        fresh.setVaultUri(ref.uri());
        fresh.setVersion(ref.version());
        fresh.setSha256(ref.sha256());
        fresh.setExpiryDate(expiryDate);
        fresh.setRecordedAt(now);
        // Business time starts when the fact was captured — the wizard does not
        // back-date documents.
        fresh.setValidFrom(now);
        DocumentEntity saved = documentRepository.saveAndFlush(fresh);

        // ADR-007 audit row, same-transaction (commits iff the row commits).
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(
                    AGGREGATE_TYPE,
                    partnerCode,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null,
                    EVENT_TYPE,
                    before,
                    DocumentJson.canonical(saved));
        }

        return saved.toView();
    }

    /**
     * The CURRENT document set for the given partner code (at most one row per
     * doc type). A partner with zero documents returns an empty list; only an
     * unknown code 404s.
     */
    @Transactional(readOnly = true)
    public List<DocumentView> currentDocuments(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return documentRepository.findCurrentByPartnerId(partner.getId()).stream()
                .map(DocumentEntity::toView)
                .toList();
    }

    /**
     * Metadata of one document row — current OR superseded (version history
     * stays addressable). 404 when the id is unknown or belongs to a different
     * partner (the row id is not guessable across partners through this path).
     */
    @Transactional(readOnly = true)
    public DocumentView metadata(String partnerCode, Long docId) {
        return requireRow(partnerCode, docId).toView();
    }

    /**
     * Download passthrough: the metadata row plus the vault object stream.
     * Caller (the controller) is responsible for closing/consuming the stream.
     *
     * @throws ResponseStatusException 404 unknown partner/doc; 502 when the
     *         vault cannot serve the object.
     */
    @Transactional(readOnly = true)
    public DocumentDownload download(String partnerCode, Long docId) {
        DocumentEntity row = requireRow(partnerCode, docId);
        try {
            VaultObject object = vaultClient.retrieve(row.getVaultUri());
            return new DocumentDownload(row.toView(), object);
        } catch (VaultException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "document vault could not serve " + row.getVaultUri() + ": " + e.getMessage(), e);
        }
    }

    /** One downloadable document: metadata row + content stream from the vault. */
    public record DocumentDownload(DocumentView meta, VaultObject object) {
    }

    // -------------------------- Helpers --------------------------------------

    private PartnerEntity requirePartner(String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    private DocumentEntity requireRow(String partnerCode, Long docId) {
        PartnerEntity partner = requirePartner(partnerCode);
        DocumentEntity row = documentRepository.findById(docId).orElse(null);
        if (row == null || !partner.getId().equals(row.getPartnerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no document " + docId + " for partner '" + partnerCode + "'");
        }
        return row;
    }

    private static DocumentType parseDocType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw badRequest("docType is required");
        }
        try {
            return DocumentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw badRequest("docType must be one of "
                    + java.util.Arrays.toString(DocumentType.values()) + ", was: " + raw);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
