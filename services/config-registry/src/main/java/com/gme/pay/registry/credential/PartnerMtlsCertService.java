package com.gme.pay.registry.credential;

import com.gme.pay.contracts.PartnerMtlsCertView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane B — owns the {@code partner_mtls_cert} child aggregate (V027)
 * behind the wizard's step-8 mTLS endpoints.
 *
 * <h2>Upload semantics</h2>
 *
 * <p>An upload replaces the CURRENT certificate binding for the
 * (partner, environment): the prior current row(s) for that environment get
 * {@code superseded_at = now} and the new ACTIVE row is INSERTed with
 * {@code recorded_at = now}, both halves sharing one MICROS-truncated instant
 * — the SCD-6 paired-write discipline of {@code PartnerSchemeService}
 * (ADR-010). Rows are never UPDATEd in place; a revoke supersedes the ACTIVE
 * row and inserts a REVOKED successor so the revocation itself is a
 * reconstructable fact.
 *
 * <h2>X.509 validation (upload time)</h2>
 *
 * <ul>
 *   <li>the PEM must parse as one X.509 certificate
 *       ({@link CertificateFactory}, no BouncyCastle);</li>
 *   <li>{@code notBefore <= now} — a not-yet-valid leaf is a 400;</li>
 *   <li>{@code notAfter > now} — an expired leaf is a 400;</li>
 *   <li>the fingerprint is SHA-256 over the DER encoding
 *       ({@link X509Certificate#getEncoded()}), lowercase hex — the exact
 *       bytes the TLS layer matches on.</li>
 * </ul>
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_mtls_cert"},
 * keyed by the partner business code, BEFORE/AFTER = {@link CredentialJson}
 * canonical snapshots (fingerprints, never PEM bodies), published inside the
 * same transaction through the {@link ObjectProvider}-resolved
 * {@link AuditLogService} — the same wiring contract as {@code RuleService}.
 */
@Service
public class PartnerMtlsCertService {

    /** Aggregate-type discriminator on audit rows for cert mutations. */
    public static final String AGGREGATE_TYPE = "partner_mtls_cert";

    /** Audit verb for a step-8 upload. */
    public static final String EVENT_TYPE_UPLOADED = "PARTNER_MTLS_CERT_UPLOADED";

    /** Audit verb for an explicit revocation. */
    public static final String EVENT_TYPE_REVOKED = "PARTNER_MTLS_CERT_REVOKED";

    /** V027 CHECK roster for environment. */
    static final Set<String> ENVIRONMENTS = Set.of("SANDBOX", "PRODUCTION");

    /** Default actor until the Keycloak {@code sub} claim is threaded through. */
    private static final String DEFAULT_ACTOR = "system";

    private final PartnerMtlsCertRepository certRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerMtlsCertService(PartnerMtlsCertRepository certRepository,
                                  PartnerRepository partnerRepository,
                                  ObjectProvider<AuditLogService> auditLogProvider) {
        this.certRepository = certRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Upload (replace) the mTLS client certificate for one environment.
     *
     * @param partnerCode the human-facing business code routing the PATCH.
     * @param environment SANDBOX | PRODUCTION.
     * @param certPem     one PEM-encoded X.509 leaf certificate.
     * @param actor       the operator (X-Actor header); {@code "system"} when absent.
     * @return the fresh ACTIVE binding as a canonical {@link PartnerMtlsCertView}.
     * @throws ResponseStatusException 404 unknown partner; 400 on roster /
     *         parse / validity-window failure; 409 when the identical cert
     *         (same fingerprint) is already the current ACTIVE binding.
     */
    @Transactional
    public PartnerMtlsCertView uploadCert(String partnerCode, String environment,
                                          String certPem, String actor) {
        requireEnvironment(environment);
        if (certPem == null || certPem.isBlank()) {
            throw badRequest("certPem is required (a PEM-encoded X.509 certificate)");
        }
        PartnerEntity partner = requirePartner(partnerCode);

        X509Certificate cert = parsePem(certPem);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant notBefore = cert.getNotBefore().toInstant().truncatedTo(ChronoUnit.MICROS);
        Instant notAfter = cert.getNotAfter().toInstant().truncatedTo(ChronoUnit.MICROS);
        if (notBefore.isAfter(now)) {
            throw badRequest("certificate is not yet valid (notBefore=" + notBefore
                    + " is in the future)");
        }
        if (!notAfter.isAfter(now)) {
            throw badRequest("certificate is expired (notAfter=" + notAfter + ")");
        }
        String fingerprint = sha256Hex(derBytes(cert));

        List<PartnerMtlsCertEntity> prior = certRepository
                .findCurrentByPartnerIdAndEnvironment(partner.getId(), environment);
        byte[] before = prior.isEmpty() ? null : CredentialJson.certs(prior);
        for (PartnerMtlsCertEntity p : prior) {
            if ("ACTIVE".equals(p.getStatus())
                    && fingerprint.equals(p.getFingerprintSha256())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "certificate with fingerprint " + fingerprint
                                + " is already the current ACTIVE binding for "
                                + environment);
            }
        }

        // SCD-6 paired write: supersede the environment's current binding(s)
        // first (flush forces the UPDATEs out before the INSERT so the V027
        // partial-unique emulation never sees two current rows per key).
        if (!prior.isEmpty()) {
            for (PartnerMtlsCertEntity p : prior) {
                p.setSupersededAt(now);
            }
            certRepository.saveAllAndFlush(prior);
        }

        PartnerMtlsCertEntity fresh = new PartnerMtlsCertEntity();
        fresh.setPartnerId(partner.getId());
        fresh.setEnvironment(environment);
        fresh.setCertPem(certPem);
        fresh.setFingerprintSha256(fingerprint);
        fresh.setSubjectDn(truncate(cert.getSubjectX500Principal().getName()));
        fresh.setIssuerDn(truncate(cert.getIssuerX500Principal().getName()));
        fresh.setNotBefore(notBefore);
        fresh.setNotAfter(notAfter);
        fresh.setStatus("ACTIVE");
        fresh.setRecordedAt(now);
        fresh.setValidFrom(now);
        // saveAndFlush + returned managed entity: the IDENTITY id is assigned
        // at flush and both the audit AFTER snapshot and the view need it.
        PartnerMtlsCertEntity saved = certRepository.saveAndFlush(fresh);

        publishAudit(partnerCode, actor, EVENT_TYPE_UPLOADED, before,
                CredentialJson.certs(List.of(saved)));

        return saved.toView();
    }

    /**
     * Revoke the CURRENT ACTIVE certificate of one environment: the ACTIVE row
     * is superseded and a REVOKED successor inserted (SCD-6 — the revocation
     * is a new fact, not an in-place edit).
     *
     * @throws ResponseStatusException 404 unknown partner or no ACTIVE binding
     *         for the environment.
     */
    @Transactional
    public PartnerMtlsCertView revokeCert(String partnerCode, String environment, String actor) {
        requireEnvironment(environment);
        PartnerEntity partner = requirePartner(partnerCode);
        List<PartnerMtlsCertEntity> current = certRepository
                .findCurrentByPartnerIdAndEnvironment(partner.getId(), environment);
        PartnerMtlsCertEntity active = current.stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no ACTIVE mTLS certificate for partner '" + partnerCode
                                + "' in environment " + environment));

        byte[] before = CredentialJson.certs(List.of(active));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        active.setSupersededAt(now);
        certRepository.saveAndFlush(active);

        PartnerMtlsCertEntity revoked = new PartnerMtlsCertEntity();
        revoked.setPartnerId(active.getPartnerId());
        revoked.setEnvironment(active.getEnvironment());
        revoked.setCertPem(active.getCertPem());
        revoked.setFingerprintSha256(active.getFingerprintSha256());
        revoked.setSubjectDn(active.getSubjectDn());
        revoked.setIssuerDn(active.getIssuerDn());
        revoked.setNotBefore(active.getNotBefore());
        revoked.setNotAfter(active.getNotAfter());
        revoked.setStatus("REVOKED");
        revoked.setRecordedAt(now);
        revoked.setValidFrom(now);
        PartnerMtlsCertEntity saved = certRepository.saveAndFlush(revoked);

        publishAudit(partnerCode, actor, EVENT_TYPE_REVOKED, before,
                CredentialJson.certs(List.of(saved)));

        return saved.toView();
    }

    /** CURRENT cert bindings of the given partner across both environments. */
    @Transactional(readOnly = true)
    public List<PartnerMtlsCertView> currentCerts(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return certRepository.findCurrentByPartnerId(partner.getId()).stream()
                .map(PartnerMtlsCertEntity::toView)
                .toList();
    }

    // -------------------------- X.509 helpers --------------------------------

    /** Parse one PEM-encoded X.509 certificate; 400 on garbage. */
    private static X509Certificate parsePem(String certPem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(certPem.getBytes(StandardCharsets.US_ASCII)));
        } catch (CertificateException | ClassCastException unparseable) {
            throw badRequest("certPem is not a parseable PEM-encoded X.509 certificate: "
                    + unparseable.getMessage());
        }
    }

    private static byte[] derBytes(X509Certificate cert) {
        try {
            return cert.getEncoded();
        } catch (CertificateException impossible) {
            // A certificate that parsed cannot fail to re-encode.
            throw new IllegalStateException("DER re-encoding failed", impossible);
        }
    }

    /** SHA-256 over the DER bytes, lowercase hex — the V027 fingerprint contract. */
    static String sha256Hex(byte[] der) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** DNs can exceed VARCHAR(255) in pathological leafs — truncate for display. */
    private static String truncate(String dn) {
        if (dn == null) {
            return null;
        }
        return dn.length() <= 255 ? dn : dn.substring(0, 255);
    }

    // -------------------------- Helpers --------------------------------------

    private static void requireEnvironment(String environment) {
        if (environment == null || !ENVIRONMENTS.contains(environment)) {
            throw badRequest("environment must be one of " + ENVIRONMENTS
                    + ", was: " + environment);
        }
    }

    private PartnerEntity requirePartner(String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    /** ADR-007 audit row, same-transaction (commits iff the business write commits). */
    private void publishAudit(String partnerCode, String actor, String eventType,
                              byte[] before, byte[] after) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(AGGREGATE_TYPE, partnerCode,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null, eventType, before, after);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
