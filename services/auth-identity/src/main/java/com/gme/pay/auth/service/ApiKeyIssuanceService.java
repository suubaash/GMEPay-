package com.gme.pay.auth.service;

import com.gme.pay.auth.dto.CredentialLookupResponse;
import com.gme.pay.auth.dto.IssueKeyRequest;
import com.gme.pay.auth.dto.IssueKeyResponse;
import com.gme.pay.auth.dto.KeyListItem;
import com.gme.pay.auth.persistence.ApiKeyEntity;
import com.gme.pay.auth.persistence.ApiKeyRepository;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Machine-credential issuance for partner principals (Slice 8 Lane B,
 * ADR-011: auth-identity owns key lifecycle).
 *
 * <h2>What one issuance produces</h2>
 *
 * <p>One {@code api_keys} row: a PUBLIC key identifier
 * ({@code api_keys.api_key} — presented in {@code X-API-Key}, not secret
 * material) plus a SECRET whose salted PBKDF2 hash is the only thing stored
 * (the V002 SEC-09 §4 contract — see {@link ApiKeyEntity#issue}). The
 * plaintext secret is returned to the caller (config-registry) EXACTLY ONCE
 * on the response and is unrecoverable afterwards.
 *
 * <p>Two purposes share the mechanism, differing only in prefixes:
 * <ul>
 *   <li>{@code API} — key id {@code pk_test_…}/{@code pk_live_…}, secret
 *       {@code sk_test_…}/{@code sk_live_…} (the HMAC request-signing
 *       secret verified by {@code AuthVerificationService});</li>
 *   <li>{@code WEBHOOK} — key id {@code whk_…}, secret {@code whsec_…} (the
 *       webhook payload-signing secret).</li>
 * </ul>
 *
 * <h2>Principal linkage</h2>
 *
 * <p>Keys hang off a PARTNER-type principal, found-or-created per
 * (partnerCode, environment) with username
 * {@code partner:{code}:{environment}} — so sandbox and production material
 * live under distinct principals and a production lock-out never strands the
 * sandbox integration.
 */
@Service
public class ApiKeyIssuanceService {

    /** Issuance purposes this service understands. */
    static final Set<String> PURPOSES = Set.of("API", "WEBHOOK");

    /** Environments mirrored from config-registry's V026..V028 rosters. */
    static final Set<String> ENVIRONMENTS = Set.of("SANDBOX", "PRODUCTION");

    /** Random length of the key identifier beyond its prefix. */
    static final int KEY_RANDOM_LENGTH = 24;

    /** Random length of the secret beyond its prefix. */
    static final int SECRET_RANDOM_LENGTH = 40;

    /** Unambiguous alphabet (no 0/O, 1/l) — tokens get read aloud and re-typed. */
    private static final char[] ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final PrincipalRepository principalRepository;

    public ApiKeyIssuanceService(ApiKeyRepository apiKeyRepository,
                                 PrincipalRepository principalRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.principalRepository = principalRepository;
    }

    /**
     * Issue one credential. The response carries the ONE-TIME plaintext
     * secret — this service has already discarded it in favour of the salted
     * hash by the time the method returns.
     *
     * @throws ResponseStatusException 400 on roster / missing-field failure.
     */
    @Transactional
    public IssueKeyResponse issue(IssueKeyRequest request) {
        validate(request);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        PrincipalEntity principal = findOrCreatePrincipal(request, now);

        String keyId = request.keyPrefix() + randomToken(KEY_RANDOM_LENGTH);
        String secret = request.secretPrefix() + randomToken(SECRET_RANDOM_LENGTH);

        Instant expiresAt = request.expiresAt() == null
                ? null : request.expiresAt().truncatedTo(ChronoUnit.MICROS);
        // saveAndFlush + the returned managed entity: the IDENTITY id lands at
        // flush time (Slice 1 lesson) and the unique api_key constraint fires
        // here rather than at commit.
        apiKeyRepository.saveAndFlush(
                ApiKeyEntity.issue(principal, keyId, secret, now, expiresAt));

        return new IssueKeyResponse(
                keyId, secret, displayPrefix(keyId), request.environment(), now, expiresAt);
    }

    /**
     * List the credentials issued for a (partnerId, environment) pair, newest
     * first. Returns only NON-secret metadata ({@link KeyListItem}) — the
     * plaintext secret is unrecoverable after issuance (SEC-09 §4).
     *
     * <p>Backs the self-serve Get-Started read-back: the Partner Portal (via
     * ops-partner-bff) lists a partner's already-minted SANDBOX keys. Keys are
     * matched on the {@code api_keys.principal}'s {@code partner_id} and the
     * {@code partner:{code}:{environment}} principal username, so a
     * {@code SANDBOX} query never returns a production credential.
     *
     * @param partnerId   config-registry partner surrogate id; required.
     * @param environment SANDBOX | PRODUCTION; required.
     */
    @Transactional(readOnly = true)
    public List<KeyListItem> listByPartnerAndEnvironment(Long partnerId, String environment) {
        if (partnerId == null) {
            throw badRequest("partnerId is required");
        }
        if (environment == null || !ENVIRONMENTS.contains(environment)) {
            throw badRequest("environment must be one of " + ENVIRONMENTS
                    + ", was: " + environment);
        }
        String envSuffix = ":" + environment;
        return principalRepository.findAll().stream()
                .filter(p -> p.getType() == PrincipalEntity.Type.PARTNER)
                .filter(p -> partnerId.equals(p.getPartnerId()))
                // Environment lives in the principal username suffix
                // (partner:{code}:{environment}); this pins the scope so a
                // SANDBOX query cannot surface a PRODUCTION principal's keys.
                .filter(p -> p.getUsername() != null && p.getUsername().endsWith(envSuffix))
                .flatMap(p -> apiKeyRepository.findByPrincipalId(p.getId()).stream())
                .sorted(Comparator.comparing(ApiKeyEntity::getCreatedAt).reversed())
                .map(k -> new KeyListItem(
                        k.getApiKey(), displayPrefix(k.getApiKey()), environment, k.getCreatedAt(),
                        // Real api_keys.status / expires_at (gap T1-3). Both non-secret; they let
                        // the portal distinguish a usable key from a revoked one.
                        k.getStatus() == null ? null : k.getStatus().name(),
                        k.getExpiresAt()))
                .toList();
    }

    /** Non-secret display prefix of a public key id: first 12 chars (or all). */
    private static String displayPrefix(String keyId) {
        if (keyId == null) {
            return null;
        }
        return keyId.length() > 12 ? keyId.substring(0, 12) : keyId;
    }

    /**
     * Revoke a credential by its public key identifier. Idempotent: unknown
     * or already-revoked keys are a no-op (the registry-side rotation flow
     * may retry).
     */
    @Transactional
    public void revoke(String keyId) {
        Optional<ApiKeyEntity> key = apiKeyRepository.findByApiKey(keyId);
        if (key.isEmpty() || key.get().getStatus() == ApiKeyEntity.Status.REVOKED) {
            return;
        }
        ApiKeyEntity entity = key.get();
        entity.revoke(Instant.now().truncatedTo(ChronoUnit.MICROS));
        apiKeyRepository.saveAndFlush(entity);
    }

    /**
     * Rotate the active credential for a (partnerCode, environment) principal:
     * revoke <em>all</em> currently-ACTIVE keys on that principal, then issue a
     * fresh one with the same prefixes. The previous secrets stop verifying
     * immediately; the caller forwards the returned one-time plaintext to the
     * partner. Atomic within one transaction.
     *
     * <p>If no principal/keys exist yet this degrades to a plain {@link #issue}.
     */
    @Transactional
    public IssueKeyResponse rotate(IssueKeyRequest request) {
        validate(request);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        String username = "partner:" + request.partnerCode() + ":" + request.environment();
        principalRepository.findByUsername(username).ifPresent(principal -> {
            List<ApiKeyEntity> existing = apiKeyRepository.findByPrincipalId(principal.getId());
            for (ApiKeyEntity key : existing) {
                if (key.getStatus() == ApiKeyEntity.Status.ACTIVE) {
                    key.revoke(now);
                    apiKeyRepository.saveAndFlush(key);
                }
            }
        });

        return issue(request);
    }

    /**
     * DB-backed credential lookup for the api-gateway: report whether the given
     * api key is a known, active, non-expired credential issued by this service,
     * and resolve its owning partner id. Returns no secret material.
     */
    @Transactional(readOnly = true)
    public CredentialLookupResponse resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return CredentialLookupResponse.notFound();
        }
        Optional<ApiKeyEntity> keyOpt = apiKeyRepository.findByApiKey(apiKey);
        if (keyOpt.isEmpty()) {
            return CredentialLookupResponse.notFound();
        }
        ApiKeyEntity key = keyOpt.get();
        Long partnerId = key.getPrincipal() == null ? null : key.getPrincipal().getPartnerId();
        boolean expired = key.getExpiresAt() != null && Instant.now().isAfter(key.getExpiresAt());
        boolean active = key.getStatus() == ApiKeyEntity.Status.ACTIVE && !expired;
        return new CredentialLookupResponse(true, active, partnerId);
    }

    // -------------------------- Helpers --------------------------------------

    private PrincipalEntity findOrCreatePrincipal(IssueKeyRequest request, Instant now) {
        String username = "partner:" + request.partnerCode() + ":" + request.environment();
        return principalRepository.findByUsername(username)
                .orElseGet(() -> principalRepository.saveAndFlush(new PrincipalEntity(
                        PrincipalEntity.Type.PARTNER,
                        username,
                        request.partnerCode() + " (" + request.environment() + ")",
                        request.partnerId(),
                        now)));
    }

    private static void validate(IssueKeyRequest request) {
        if (request == null) {
            throw badRequest("request body is required");
        }
        if (request.partnerCode() == null || request.partnerCode().isBlank()) {
            throw badRequest("partnerCode is required");
        }
        if (request.environment() == null || !ENVIRONMENTS.contains(request.environment())) {
            throw badRequest("environment must be one of " + ENVIRONMENTS
                    + ", was: " + request.environment());
        }
        if (request.purpose() == null || !PURPOSES.contains(request.purpose())) {
            throw badRequest("purpose must be one of " + PURPOSES
                    + ", was: " + request.purpose());
        }
        if (request.keyPrefix() == null || request.keyPrefix().isBlank()) {
            throw badRequest("keyPrefix is required (e.g. pk_test_)");
        }
        if (request.secretPrefix() == null || request.secretPrefix().isBlank()) {
            throw badRequest("secretPrefix is required (e.g. sk_test_)");
        }
        // api_keys.api_key is VARCHAR(64); fail loudly rather than truncate.
        if (request.keyPrefix().length() + KEY_RANDOM_LENGTH > 64) {
            throw badRequest("keyPrefix too long: the key identifier would exceed 64 chars");
        }
    }

    private static String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
