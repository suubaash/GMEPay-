package com.gme.pay.auth.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Local H2 (PostgreSQL-compat mode) slice test for the V002 identity tables:
 * principals / roles / api_keys round-trips through the Spring Data repositories.
 *
 * <p>This is the no-Docker unit slice that runs on every machine. The same
 * scenarios run against a real PostgreSQL 16 in
 * {@link IdentityPersistencePostgresIT} (docker-tagged, CI only) per ticket
 * 17.2-G09 — H2 stays for pure unit slices only.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdentityRepositoriesSliceTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PrincipalRepository principalRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Test
    void v002SeedsRoleCatalogue() {
        assertThat(roleRepository.findByCode("HUB_ADMIN")).isPresent();
        assertThat(roleRepository.findByCode("HUB_OPERATOR")).isPresent();
        assertThat(roleRepository.findByCode("PARTNER_API")).isPresent();
    }

    @Test
    void principalRoundTrip_withRoles() {
        PrincipalEntity principal = new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "ops.kim", "Kim Min-ji", null, Instant.now());
        principal.addRole(roleRepository.findByCode("HUB_OPERATOR").orElseThrow());
        principalRepository.save(principal);
        em.flush();
        em.clear();

        Optional<PrincipalEntity> reloaded = principalRepository.findByUsername("ops.kim");

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(PrincipalEntity.Status.ACTIVE);
        assertThat(reloaded.get().getRoles())
                .extracting(RoleEntity::getCode)
                .containsExactly("HUB_OPERATOR");
    }

    @Test
    void apiKeyRoundTrip_storesSaltedHashNeverPlaintext() {
        String plaintextSecret = "super-secret-hmac-material-XYZ";
        PrincipalEntity principal = principalRepository.save(new PrincipalEntity(
                PrincipalEntity.Type.PARTNER, "partner-7", "GME Partner 7", 7L, Instant.now()));
        apiKeyRepository.save(ApiKeyEntity.issue(
                principal, "ak_live_0001", plaintextSecret, Instant.now()));
        em.flush();
        em.clear();

        ApiKeyEntity reloaded = apiKeyRepository.findByApiKey("ak_live_0001").orElseThrow();

        // Round-trip + verification through the persisted derivation parameters.
        assertThat(reloaded.getStatus()).isEqualTo(ApiKeyEntity.Status.ACTIVE);
        assertThat(reloaded.getPrincipal().getPartnerId()).isEqualTo(7L);
        assertThat(reloaded.secretMatches(plaintextSecret)).isTrue();
        assertThat(reloaded.secretMatches("not-the-secret")).isFalse();

        // SEC-09 §4: only the salted hash is at rest — never the plaintext.
        assertThat(reloaded.getSecretHash()).matches("[0-9a-f]{64}");
        assertThat(reloaded.getSecretHash()).isNotEqualTo(plaintextSecret);
        assertThat(reloaded.getSecretHash()).doesNotContain(plaintextSecret);
        assertThat(reloaded.getSecretSalt()).matches("[0-9a-f]{32}");
        assertThat(reloaded.getHashAlgorithm()).isEqualTo("PBKDF2WithHmacSHA256");
        assertThat(reloaded.getHashIterations()).isPositive();
    }

    @Test
    void duplicateUsername_violatesUniqueConstraint() {
        principalRepository.saveAndFlush(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "dup.user", null, null, Instant.now()));

        assertThatThrownBy(() -> principalRepository.saveAndFlush(new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "dup.user", null, null, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
