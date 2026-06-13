package com.gme.pay.registry.credential;

import com.gme.pay.contracts.IssuedCredentialBundle;
import com.gme.pay.contracts.PartnerCredentialView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.client.AuthIdentityClient;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane B — owns the {@code partner_credential} ledger (V028) and the
 * credential issuance / rotation / revocation flows against auth-identity.
 *
 * <h2>Issuance flow</h2>
 *
 * <p>On the lifecycle transition to SANDBOX (and again on the transition to
 * LIVE — see {@link #issueForTransition}) the lifecycle service calls
 * {@link #issueCredentials} INSIDE the transition transaction. Two
 * auth-identity calls mint the material:
 * <ol>
 *   <li>purpose {@code API} — the API key public identifier
 *       ({@code pk_test_…} / {@code pk_live_…}) plus its HMAC signing secret
 *       ({@code sk_test_…} / {@code sk_live_…}), one {@code api_keys} row
 *       over there (salted PBKDF2 hash at rest, V002 contract);</li>
 *   <li>purpose {@code WEBHOOK} — the webhook signing secret
 *       ({@code whsec_test_…} / {@code whsec_live_…}).</li>
 * </ol>
 * Three ledger rows land here (API_KEY / HMAC_SECRET / WEBHOOK_SECRET) with
 * the key id + prefix + last-4 residue ONLY, and the one-time plaintext
 * bundle ({@link IssuedCredentialBundle}) rides the activation response —
 * after which it is unrecoverable by design (SEC-09 §4).
 *
 * <h2>Rotation</h2>
 *
 * <p>{@link #rotateCredentials} marks the environment's ACTIVE rows ROTATED,
 * revokes the old auth-identity keys, and issues a fresh bundle in one
 * transaction. The 11-month scheduler
 * ({@link PartnerCredentialRotationScheduler}) PROPOSES rotation through the
 * 4-eyes change_request flow; the rotation itself stays an explicit call.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_credential"},
 * keyed by the partner business code, BEFORE/AFTER = {@link CredentialJson}
 * canonical snapshots (display residue only — no secret material can reach
 * the audit chain), published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code RuleService}.
 */
@Service
public class PartnerCredentialService {

    /** Aggregate-type discriminator on audit rows for credential mutations. */
    public static final String AGGREGATE_TYPE = "partner_credential";

    public static final String EVENT_TYPE_ISSUED = "PARTNER_CREDENTIALS_ISSUED";
    public static final String EVENT_TYPE_ROTATED = "PARTNER_CREDENTIALS_ROTATED";
    public static final String EVENT_TYPE_REVOKED = "PARTNER_CREDENTIALS_REVOKED";

    /** V028 CHECK roster for environment. */
    static final Set<String> ENVIRONMENTS = Set.of("SANDBOX", "PRODUCTION");

    /**
     * Issued material is valid for 12 months; the rotation scheduler proposes
     * replacement at the 11-month mark, leaving a one-month overlap window.
     */
    static final int VALIDITY_MONTHS = 12;

    /** Default actor until the Keycloak {@code sub} claim is threaded through. */
    private static final String DEFAULT_ACTOR = "system";

    private final PartnerCredentialRepository credentialRepository;
    private final PartnerRepository partnerRepository;
    private final AuthIdentityClient authIdentityClient;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerCredentialService(PartnerCredentialRepository credentialRepository,
                                    PartnerRepository partnerRepository,
                                    AuthIdentityClient authIdentityClient,
                                    ObjectProvider<AuditLogService> auditLogProvider) {
        this.credentialRepository = credentialRepository;
        this.partnerRepository = partnerRepository;
        this.authIdentityClient = authIdentityClient;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Lifecycle hook for the Slice 8 transition service: issues the right
     * credential tier when the partner enters SANDBOX (sandbox keys) or LIVE
     * (production keys); any other target status issues nothing. Call this
     * INSIDE the transition transaction so a failed issuance rolls the
     * transition back (no partner ends up SANDBOX without credentials).
     *
     * <p>IDEMPOTENT on re-entry: when the environment already carries ACTIVE
     * credentials the hook issues nothing and returns empty — a REACTIVATE
     * (SUSPENDED → LIVE) must not re-mint production keys nor fail the
     * transition. The strict 409 path is {@link #issueCredentials}, the
     * explicit re-mint path is {@link #rotateCredentials}.
     *
     * @return the one-time plaintext bundle to ride the activation response,
     *         or empty when the target status carries no issuance (or the
     *         tier is already provisioned).
     */
    @Transactional
    public Optional<IssuedCredentialBundle> issueForTransition(String partnerCode,
                                                               String targetStatus,
                                                               String actor) {
        String environment = switch (targetStatus == null ? "" : targetStatus) {
            case "SANDBOX" -> "SANDBOX";
            case "LIVE" -> "PRODUCTION";
            default -> null;
        };
        if (environment == null) {
            return Optional.empty();
        }
        PartnerEntity partner = requirePartner(partnerCode);
        List<PartnerCredentialEntity> active = credentialRepository
                .findByPartnerIdAndEnvironmentAndStatus(partner.getId(), environment, "ACTIVE");
        if (!active.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                issueFresh(partner, partnerCode, environment, actor, EVENT_TYPE_ISSUED, null));
    }

    /**
     * Issue the API key + HMAC secret + webhook secret set for one environment.
     *
     * @throws ResponseStatusException 404 unknown partner; 400 bad environment;
     *         409 {@code CREDENTIALS_ALREADY_ISSUED} when the environment
     *         already carries ACTIVE credentials (rotate instead).
     */
    @Transactional
    public IssuedCredentialBundle issueCredentials(String partnerCode, String environment,
                                                   String actor) {
        requireEnvironment(environment);
        PartnerEntity partner = requirePartner(partnerCode);
        List<PartnerCredentialEntity> active = credentialRepository
                .findByPartnerIdAndEnvironmentAndStatus(partner.getId(), environment, "ACTIVE");
        if (!active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "CREDENTIALS_ALREADY_ISSUED: partner '" + partnerCode
                            + "' already has ACTIVE " + environment
                            + " credentials — rotate instead of re-issuing");
        }
        return issueFresh(partner, partnerCode, environment, actor, EVENT_TYPE_ISSUED, null);
    }

    /**
     * Rotate the environment's ACTIVE credential set: marks the current rows
     * ROTATED, revokes the old auth-identity keys, issues a fresh set and
     * returns its one-time plaintext bundle.
     *
     * @throws ResponseStatusException 404 unknown partner; 400 bad environment;
     *         409 when the environment has no ACTIVE credentials to rotate.
     */
    @Transactional
    public IssuedCredentialBundle rotateCredentials(String partnerCode, String environment,
                                                    String actor) {
        requireEnvironment(environment);
        PartnerEntity partner = requirePartner(partnerCode);
        List<PartnerCredentialEntity> active = credentialRepository
                .findByPartnerIdAndEnvironmentAndStatus(partner.getId(), environment, "ACTIVE");
        if (active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' has no ACTIVE " + environment
                            + " credentials to rotate");
        }
        byte[] before = CredentialJson.credentials(active);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Set<String> oldKeyIds = new LinkedHashSet<>();
        for (PartnerCredentialEntity row : active) {
            row.setStatus("ROTATED");
            row.setRotatedAt(now);
            if (row.getAuthIdentityKeyId() != null) {
                oldKeyIds.add(row.getAuthIdentityKeyId());
            }
        }
        credentialRepository.saveAllAndFlush(active);
        for (String oldKeyId : oldKeyIds) {
            authIdentityClient.revokeKey(oldKeyId);
        }

        return issueFresh(partner, partnerCode, environment, actor, EVENT_TYPE_ROTATED, before);
    }

    /**
     * Revoke the environment's ACTIVE credential set without replacement
     * (suspension / termination path).
     *
     * @return the revoked rows as views.
     * @throws ResponseStatusException 404 unknown partner; 400 bad environment;
     *         409 when there is nothing ACTIVE to revoke.
     */
    @Transactional
    public List<PartnerCredentialView> revokeCredentials(String partnerCode, String environment,
                                                         String actor) {
        requireEnvironment(environment);
        PartnerEntity partner = requirePartner(partnerCode);
        List<PartnerCredentialEntity> active = credentialRepository
                .findByPartnerIdAndEnvironmentAndStatus(partner.getId(), environment, "ACTIVE");
        if (active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' has no ACTIVE " + environment
                            + " credentials to revoke");
        }
        byte[] before = CredentialJson.credentials(active);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Set<String> keyIds = new LinkedHashSet<>();
        for (PartnerCredentialEntity row : active) {
            row.setStatus("REVOKED");
            row.setRevokedAt(now);
            if (row.getAuthIdentityKeyId() != null) {
                keyIds.add(row.getAuthIdentityKeyId());
            }
        }
        List<PartnerCredentialEntity> saved = credentialRepository.saveAllAndFlush(active);
        for (String keyId : keyIds) {
            authIdentityClient.revokeKey(keyId);
        }

        publishAudit(partnerCode, actor, EVENT_TYPE_REVOKED, before,
                CredentialJson.credentials(saved));
        return saved.stream().map(PartnerCredentialEntity::toView).toList();
    }

    /** Full credential ledger of the given partner (non-plaintext views), newest first. */
    @Transactional(readOnly = true)
    public List<PartnerCredentialView> listCredentials(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return credentialRepository
                .findByPartnerIdOrderByIssuedAtDescIdDesc(partner.getId()).stream()
                .map(PartnerCredentialEntity::toView)
                .toList();
    }

    // -------------------------- Issuance core --------------------------------

    /**
     * Mint one full credential set through auth-identity and ledger it.
     * Shared by first issuance and rotation (after the rotation has retired
     * the prior rows).
     */
    private IssuedCredentialBundle issueFresh(PartnerEntity partner, String partnerCode,
                                              String environment, String actor,
                                              String eventType, byte[] before) {
        String envToken = "SANDBOX".equals(environment) ? "test" : "live";
        String keyPrefix = "pk_" + envToken + "_";
        String hmacPrefix = "sk_" + envToken + "_";
        String webhookKeyPrefix = "whk_" + envToken + "_";
        String webhookSecretPrefix = "whsec_" + envToken + "_";

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.atOffset(ZoneOffset.UTC)
                .plusMonths(VALIDITY_MONTHS).toInstant().truncatedTo(ChronoUnit.MICROS);

        AuthIdentityClient.IssuedKey apiKey = authIdentityClient.issueKey(
                new AuthIdentityClient.IssueKeyCommand(partner.getId(), partnerCode,
                        environment, "API", keyPrefix, hmacPrefix, expiresAt));
        AuthIdentityClient.IssuedKey webhookKey = authIdentityClient.issueKey(
                new AuthIdentityClient.IssueKeyCommand(partner.getId(), partnerCode,
                        environment, "WEBHOOK", webhookKeyPrefix, webhookSecretPrefix,
                        expiresAt));

        List<PartnerCredentialEntity> fresh = new ArrayList<>(3);
        fresh.add(ledgerRow(partner.getId(), environment, "API_KEY",
                apiKey.keyId(), keyPrefix, last4(apiKey.keyId()), now, expiresAt));
        fresh.add(ledgerRow(partner.getId(), environment, "HMAC_SECRET",
                apiKey.keyId(), hmacPrefix, last4(apiKey.secretPlaintext()), now, expiresAt));
        fresh.add(ledgerRow(partner.getId(), environment, "WEBHOOK_SECRET",
                webhookKey.keyId(), webhookSecretPrefix,
                last4(webhookKey.secretPlaintext()), now, expiresAt));
        // saveAllAndFlush: IDENTITY ids land on the returned managed entities,
        // which the audit AFTER snapshot needs.
        List<PartnerCredentialEntity> saved = credentialRepository.saveAllAndFlush(fresh);

        publishAudit(partnerCode, actor, eventType, before,
                CredentialJson.credentials(saved));

        // The ONE-TIME plaintext bundle: never logged, never persisted —
        // assembled here, returned to the caller, gone (SEC-09 §4).
        return new IssuedCredentialBundle(
                apiKey.keyId(),
                apiKey.secretPlaintext(),
                webhookKey.secretPlaintext(),
                apiKey.keyId(),
                keyPrefix,
                last4(apiKey.keyId()),
                expiresAt);
    }

    private static PartnerCredentialEntity ledgerRow(Long partnerId, String environment,
                                                     String kind, String keyId, String prefix,
                                                     String last4, Instant issuedAt,
                                                     Instant expiresAt) {
        PartnerCredentialEntity e = new PartnerCredentialEntity();
        e.setPartnerId(partnerId);
        e.setEnvironment(environment);
        e.setCredentialKind(kind);
        e.setAuthIdentityKeyId(keyId);
        e.setPrefix(prefix);
        e.setLast4(last4);
        e.setIssuedAt(issuedAt);
        e.setExpiresAt(expiresAt);
        e.setStatus("ACTIVE");
        return e;
    }

    private static String last4(String material) {
        if (material == null || material.length() < 4) {
            return null;
        }
        return material.substring(material.length() - 4);
    }

    // -------------------------- Helpers --------------------------------------

    private static void requireEnvironment(String environment) {
        if (environment == null || !ENVIRONMENTS.contains(environment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "environment must be one of " + ENVIRONMENTS + ", was: " + environment);
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
}
