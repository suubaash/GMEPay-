package com.gme.pay.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.auth.dto.IssueKeyRequest;
import com.gme.pay.auth.dto.IssueKeyResponse;
import com.gme.pay.auth.persistence.ApiKeyEntity;
import com.gme.pay.auth.persistence.ApiKeyRepository;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 8 Lane B acceptance test for {@link ApiKeyIssuanceService} — the
 * internal key-issuance flow behind {@code POST /internal/auth/keys}.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>SEC-09 §4: the issued row stores the salted PBKDF2 hash + parameters,
 *       NEVER the plaintext; the returned plaintext verifies through
 *       {@link ApiKeyEntity#secretMatches} and the response redacts it from
 *       {@code toString()}.</li>
 *   <li>Prefix contract: key id + secret carry the requested prefixes;
 *       expiry is persisted MICROS-truncated.</li>
 *   <li>Principal linkage: a PARTNER principal is found-or-created per
 *       (partnerCode, environment) — two issuances share it, environments
 *       split it.</li>
 *   <li>Revocation: idempotent, stamps revoked_at, flips status.</li>
 *   <li>Validation: roster / missing-field / over-length 400s.</li>
 * </ol>
 */
@DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc
                .AutoConfigureTestDatabase.Replace.NONE)
@Import(ApiKeyIssuanceService.class)
class ApiKeyIssuanceServiceTest {

    @Autowired
    private ApiKeyIssuanceService service;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private PrincipalRepository principalRepository;

    private static IssueKeyRequest request(String code, String env, String purpose,
                                           String keyPrefix, String secretPrefix,
                                           Instant expiresAt) {
        return new IssueKeyRequest(42L, code, env, purpose, keyPrefix, secretPrefix,
                expiresAt);
    }

    @Test
    void issue_storesSaltedHashOnly_plaintextVerifies_neverPersisted() {
        Instant expiry = Instant.now().plusSeconds(86400 * 365).truncatedTo(ChronoUnit.MICROS);
        IssueKeyResponse response = service.issue(
                request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", expiry));

        assertThat(response.keyId()).startsWith("pk_test_").hasSize(8 + 24);
        assertThat(response.secretPlaintext()).startsWith("sk_test_").hasSize(8 + 40);
        assertThat(response.expiresAt()).isEqualTo(expiry);

        ApiKeyEntity row = apiKeyRepository.findByApiKey(response.keyId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ApiKeyEntity.Status.ACTIVE);
        assertThat(row.getExpiresAt()).isEqualTo(expiry);
        // The hash columns never contain the plaintext...
        assertThat(row.getSecretHash()).isNotEqualTo(response.secretPlaintext());
        assertThat(row.getSecretHash()).doesNotContain(response.secretPlaintext());
        // ...but the plaintext re-derives to the stored hash (constant-time).
        assertThat(row.secretMatches(response.secretPlaintext())).isTrue();
        assertThat(row.secretMatches("sk_test_wrong")).isFalse();

        // A stray logger call on the response leaks nothing.
        assertThat(response.toString())
                .contains("REDACTED")
                .doesNotContain(response.secretPlaintext());
    }

    @Test
    void principalLinkage_foundOrCreatedPerPartnerAndEnvironment() {
        service.issue(request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", null));
        service.issue(request("GMEREMIT", "SANDBOX", "WEBHOOK", "whk_test_", "whsec_test_",
                null));
        service.issue(request("GMEREMIT", "PRODUCTION", "API", "pk_live_", "sk_live_", null));

        PrincipalEntity sandbox = principalRepository
                .findByUsername("partner:GMEREMIT:SANDBOX").orElseThrow();
        PrincipalEntity production = principalRepository
                .findByUsername("partner:GMEREMIT:PRODUCTION").orElseThrow();
        assertThat(sandbox.getType()).isEqualTo(PrincipalEntity.Type.PARTNER);
        assertThat(sandbox.getPartnerId()).isEqualTo(42L);
        assertThat(sandbox.getId()).isNotEqualTo(production.getId());

        // Both sandbox keys hang off the ONE sandbox principal.
        assertThat(apiKeyRepository.findByPrincipalId(sandbox.getId())).hasSize(2);
        assertThat(apiKeyRepository.findByPrincipalId(production.getId())).hasSize(1);
    }

    @Test
    void revoke_flipsStatusAndStampsRevokedAt_idempotent() {
        IssueKeyResponse response = service.issue(
                request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", null));

        service.revoke(response.keyId());
        ApiKeyEntity row = apiKeyRepository.findByApiKey(response.keyId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ApiKeyEntity.Status.REVOKED);
        assertThat(row.getRevokedAt()).isNotNull();
        Instant firstRevokedAt = row.getRevokedAt();

        // Idempotent: revoking again (or revoking garbage) is a silent no-op.
        service.revoke(response.keyId());
        service.revoke("pk_test_doesNotExist");
        assertThat(apiKeyRepository.findByApiKey(response.keyId()).orElseThrow()
                .getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    @Test
    void validation_rosterAndMissingFields_are400s() {
        assertBadRequest(request(null, "SANDBOX", "API", "pk_test_", "sk_test_", null));
        assertBadRequest(request("GMEREMIT", "STAGING", "API", "pk_test_", "sk_test_", null));
        assertBadRequest(request("GMEREMIT", "SANDBOX", "PASSWORD", "pk_test_", "sk_test_",
                null));
        assertBadRequest(request("GMEREMIT", "SANDBOX", "API", null, "sk_test_", null));
        assertBadRequest(request("GMEREMIT", "SANDBOX", "API", "pk_test_", null, null));
        // 41-char prefix + 24 random would blow the VARCHAR(64) column.
        assertBadRequest(request("GMEREMIT", "SANDBOX", "API", "x".repeat(41), "sk_test_",
                null));
        assertThat(apiKeyRepository.count()).isZero();
    }

    @Test
    void issuedTokens_areUniqueAcrossCalls() {
        IssueKeyResponse a = service.issue(
                request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", null));
        IssueKeyResponse b = service.issue(
                request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", null));
        assertThat(a.keyId()).isNotEqualTo(b.keyId());
        assertThat(a.secretPlaintext()).isNotEqualTo(b.secretPlaintext());
    }

    @Test
    void rotate_revokesPriorActiveKeys_thenIssuesFreshOne() {
        IssueKeyResponse first = service.issue(
                request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", null));
        IssueKeyResponse rotated = service.rotate(
                request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", null));

        assertThat(rotated.keyId()).isNotEqualTo(first.keyId());

        // The old key is revoked; the new key is the only ACTIVE one.
        ApiKeyEntity oldRow = apiKeyRepository.findByApiKey(first.keyId()).orElseThrow();
        ApiKeyEntity newRow = apiKeyRepository.findByApiKey(rotated.keyId()).orElseThrow();
        assertThat(oldRow.getStatus()).isEqualTo(ApiKeyEntity.Status.REVOKED);
        assertThat(oldRow.getRevokedAt()).isNotNull();
        assertThat(newRow.getStatus()).isEqualTo(ApiKeyEntity.Status.ACTIVE);

        PrincipalEntity principal = principalRepository
                .findByUsername("partner:GMEREMIT:SANDBOX").orElseThrow();
        long activeCount = apiKeyRepository.findByPrincipalId(principal.getId()).stream()
                .filter(k -> k.getStatus() == ApiKeyEntity.Status.ACTIVE)
                .count();
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    void rotate_withNoExistingPrincipal_behavesLikeIssue() {
        IssueKeyResponse rotated = service.rotate(
                request("NEWPARTNER", "SANDBOX", "API", "pk_test_", "sk_test_", null));
        assertThat(rotated.keyId()).startsWith("pk_test_");
        assertThat(apiKeyRepository.findByApiKey(rotated.keyId()).orElseThrow().getStatus())
                .isEqualTo(ApiKeyEntity.Status.ACTIVE);
    }

    @Test
    void resolve_returnsFoundActiveAndPartnerId_forIssuedKey() {
        IssueKeyResponse issued = service.issue(
                request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", null));

        var lookup = service.resolve(issued.keyId());
        assertThat(lookup.found()).isTrue();
        assertThat(lookup.active()).isTrue();
        assertThat(lookup.partnerId()).isEqualTo(42L);
    }

    @Test
    void resolve_unknownKey_isNotFound() {
        var lookup = service.resolve("pk_test_neverIssued");
        assertThat(lookup.found()).isFalse();
        assertThat(lookup.active()).isFalse();
        assertThat(lookup.partnerId()).isNull();
    }

    @Test
    void resolve_blankOrNull_isNotFound() {
        assertThat(service.resolve(null).found()).isFalse();
        assertThat(service.resolve("  ").found()).isFalse();
    }

    @Test
    void resolve_revokedKey_isFoundButInactive() {
        IssueKeyResponse issued = service.issue(
                request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", null));
        service.revoke(issued.keyId());

        var lookup = service.resolve(issued.keyId());
        assertThat(lookup.found()).isTrue();
        assertThat(lookup.active()).isFalse();
        assertThat(lookup.partnerId()).isEqualTo(42L);
    }

    @Test
    void resolve_expiredKey_isFoundButInactive() {
        Instant past = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        IssueKeyResponse issued = service.issue(
                request("GMEREMIT", "SANDBOX", "API", "pk_test_", "sk_test_", past));

        var lookup = service.resolve(issued.keyId());
        assertThat(lookup.found()).isTrue();
        assertThat(lookup.active()).isFalse();
    }

    private void assertBadRequest(IssueKeyRequest request) {
        assertThatThrownBy(() -> service.issue(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
