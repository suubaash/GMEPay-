package com.gme.pay.auth.persistence;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL 16 integration test for the auth-identity schema and
 * repositories (ticket 17.2-G09): Flyway migrations V001+V002 run against a
 * Testcontainers postgres:16, then principals / roles / api_keys round-trip
 * through the Spring Data repositories, and the salted-hash secret convention
 * (SEC-09 §4) is asserted at the SQL level.
 *
 * <p>Docker-tagged: excluded from the normal {@code test} task (this machine
 * has no Docker) and executed by the {@code integrationTest} task on CI's
 * ubuntu runners — see root build.gradle and .github/workflows/ci.yml.</p>
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaNonceStore.class)
class IdentityPersistencePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PrincipalRepository principalRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private JpaNonceStore nonceStore;

    @Test
    void runsAgainstRealPostgres16_withAllMigrationsApplied() {
        String version = jdbc.queryForObject("SELECT version()", String.class);
        assertThat(version).as("must be a real PostgreSQL, not H2").contains("PostgreSQL 16");

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(history)
                .extracting(row -> row.get("version"))
                .as("foundational migrations applied (list grows as later slices add migrations)")
                .contains("001", "002");
        assertThat(history)
                .allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    void roleRoundTrip_andSeededCatalogue() {
        assertThat(roleRepository.findByCode("HUB_ADMIN")).isPresent();
        assertThat(roleRepository.findByCode("HUB_OPERATOR")).isPresent();
        assertThat(roleRepository.findByCode("PARTNER_API")).isPresent();

        roleRepository.save(new RoleEntity("RECON_VIEWER", "Read-only recon access", Instant.now()));
        em.flush();
        em.clear();

        assertThat(roleRepository.findByCode("RECON_VIEWER"))
                .isPresent()
                .get()
                .satisfies(role -> {
                    assertThat(role.getId()).as("BIGSERIAL id assigned by PG").isNotNull();
                    assertThat(role.getDescription()).isEqualTo("Read-only recon access");
                });
    }

    @Test
    void principalRoundTrip_withRolesJoinTable() {
        PrincipalEntity principal = new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, "ops.park", "Park Ji-sung", null, Instant.now());
        principal.addRole(roleRepository.findByCode("HUB_ADMIN").orElseThrow());
        principal.addRole(roleRepository.findByCode("HUB_OPERATOR").orElseThrow());
        principalRepository.save(principal);
        em.flush();
        em.clear();

        PrincipalEntity reloaded = principalRepository.findByUsername("ops.park").orElseThrow();

        assertThat(reloaded.getType()).isEqualTo(PrincipalEntity.Type.OPERATOR);
        assertThat(reloaded.getStatus()).isEqualTo(PrincipalEntity.Status.ACTIVE);
        assertThat(reloaded.getRoles())
                .extracting(RoleEntity::getCode)
                .containsExactlyInAnyOrder("HUB_ADMIN", "HUB_OPERATOR");

        Integer joinRows = jdbc.queryForObject(
                "SELECT count(*) FROM principal_roles pr JOIN principals p ON p.id = pr.principal_id"
                        + " WHERE p.username = ?",
                Integer.class, "ops.park");
        assertThat(joinRows).isEqualTo(2);
    }

    @Test
    void apiKeyRoundTrip_secretAtRestIsSaltedHashNeverPlaintext() {
        String plaintextSecret = "pg-hmac-secret-material-789";
        PrincipalEntity principal = principalRepository.save(new PrincipalEntity(
                PrincipalEntity.Type.PARTNER, "partner-42", "GME Partner 42", 42L, Instant.now()));
        apiKeyRepository.save(ApiKeyEntity.issue(
                principal, "ak_pg_0042", plaintextSecret, Instant.now()));
        em.flush();
        em.clear();

        ApiKeyEntity reloaded = apiKeyRepository.findByApiKey("ak_pg_0042").orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(ApiKeyEntity.Status.ACTIVE);
        assertThat(reloaded.getPrincipal().getUsername()).isEqualTo("partner-42");
        assertThat(reloaded.secretMatches(plaintextSecret)).isTrue();
        assertThat(reloaded.secretMatches("wrong-secret")).isFalse();

        // SEC-09 §4 at the SQL level: what PostgreSQL actually stores is the
        // salted PBKDF2 digest + parameters — the plaintext appears nowhere.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT secret_hash, secret_salt, hash_algorithm, hash_iterations"
                        + " FROM api_keys WHERE api_key = ?", "ak_pg_0042");
        assertThat((String) row.get("secret_hash")).matches("[0-9a-f]{64}");
        assertThat((String) row.get("secret_hash")).isNotEqualTo(plaintextSecret);
        assertThat((String) row.get("secret_hash")).doesNotContain(plaintextSecret);
        assertThat((String) row.get("secret_salt")).matches("[0-9a-f]{32}");
        assertThat(row.get("hash_algorithm")).isEqualTo("PBKDF2WithHmacSHA256");
        assertThat((Integer) row.get("hash_iterations")).isPositive();
    }

    @Test
    void nonceStoreRoundTrip_onRealPostgres() {
        Duration ttl = Duration.ofSeconds(600);

        boolean first = nonceStore.checkAndSet("42", "pg-nonce-A", ttl);
        boolean replay = nonceStore.checkAndSet("42", "pg-nonce-A", ttl);

        assertThat(first).as("first sighting inserts").isTrue();
        assertThat(replay).as("second sighting is a replay").isFalse();
    }

    @Test
    void duplicateApiKey_violatesUniqueConstraint() {
        PrincipalEntity principal = principalRepository.save(new PrincipalEntity(
                PrincipalEntity.Type.SERVICE, "svc.gateway", null, null, Instant.now()));
        apiKeyRepository.saveAndFlush(ApiKeyEntity.issue(
                principal, "ak_dup_0001", "secret-one", Instant.now()));

        assertThatThrownBy(() -> apiKeyRepository.saveAndFlush(ApiKeyEntity.issue(
                principal, "ak_dup_0001", "secret-two", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
