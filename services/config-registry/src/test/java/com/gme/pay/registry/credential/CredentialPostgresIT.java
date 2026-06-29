package com.gme.pay.registry.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Slice 8 Lane B: real-PostgreSQL integration tests for the V026 / V027 /
 * V028 credential tables. Boots the full service against a postgres:16
 * Testcontainer so the engine-neutral SQL runs on the production engine
 * (the H2 twins are what every {@code @DataJpaTest} slice exercises).
 *
 * <p>Docker-tagged: excluded from the local {@code test} task and executed by
 * the {@code integrationTest} task on CI ubuntu runners — same contract as
 * {@code PartnerCorridorPostgresIT}.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class CredentialPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private JdbcTemplate jdbc;

    /** The GMEREMIT seed row's surrogate id (PartnerSeeder runs in the full context). */
    private Long seededPartnerId() {
        return jdbc.queryForObject(
                "select id from partners where partner_code = 'GMEREMIT'"
                        + " and superseded_at is null", Long.class);
    }

    @Test
    void v026Through28_applyOnPostgres16() {
        for (String version : new String[] {"026", "027", "028"}) {
            Integer applied = jdbc.queryForObject(
                    "select count(*) from flyway_schema_history"
                            + " where version = ? and success = true",
                    Integer.class, version);
            assertThat(applied).as("V%s must apply on PG16", version).isEqualTo(1);
        }
    }

    @Test
    void v026_uniqueAndCheckConstraints_fireOnPostgres() {
        Long partnerId = seededPartnerId();
        jdbc.update("insert into partner_ip_allowlist"
                        + " (partner_id, cidr, environment, created_by)"
                        + " values (?, '203.0.113.0/24', 'SANDBOX', 'it')", partnerId);

        // Duplicate (partner, environment, cidr) collides on the UNIQUE.
        assertThatThrownBy(() -> jdbc.update("insert into partner_ip_allowlist"
                        + " (partner_id, cidr, environment, created_by)"
                        + " values (?, '203.0.113.0/24', 'SANDBOX', 'it')", partnerId))
                .isInstanceOf(DataAccessException.class);

        // The environment roster CHECK fires.
        assertThatThrownBy(() -> jdbc.update("insert into partner_ip_allowlist"
                        + " (partner_id, cidr, environment, created_by)"
                        + " values (?, '203.0.113.0/24', 'STAGING', 'it')", partnerId))
                .isInstanceOf(DataAccessException.class);

        // The trivial cidr-shape CHECK fires (no slash).
        assertThatThrownBy(() -> jdbc.update("insert into partner_ip_allowlist"
                        + " (partner_id, cidr, environment, created_by)"
                        + " values (?, '203.0.113.0', 'SANDBOX', 'it')", partnerId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void v027_partialUniqueEmulation_oneCurrentRowPerCertKey() {
        Long partnerId = seededPartnerId();
        String fingerprint = "a".repeat(64);
        String key = partnerId + ":SANDBOX:" + fingerprint;

        jdbc.update("insert into partner_mtls_cert (partner_id, environment, cert_pem,"
                + " fingerprint_sha256, status, current_cert_key)"
                + " values (?, 'SANDBOX', 'PEM', ?, 'ACTIVE', ?)",
                partnerId, fingerprint, key);

        // Second CURRENT row for the same key collides.
        assertThatThrownBy(() -> jdbc.update("insert into partner_mtls_cert (partner_id,"
                        + " environment, cert_pem, fingerprint_sha256, status,"
                        + " current_cert_key) values (?, 'SANDBOX', 'PEM', ?, 'ACTIVE', ?)",
                partnerId, fingerprint, key))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("partner_mtls_cert_current");

        // Supersede vacates the slot (the app NULLs the key, V022 discipline);
        // a fresh current row for the same fingerprint then inserts cleanly.
        jdbc.update("update partner_mtls_cert set superseded_at = now(),"
                + " current_cert_key = null where current_cert_key = ?", key);
        jdbc.update("insert into partner_mtls_cert (partner_id, environment, cert_pem,"
                + " fingerprint_sha256, status, current_cert_key)"
                + " values (?, 'SANDBOX', 'PEM', ?, 'ACTIVE', ?)",
                partnerId, fingerprint, key);

        Integer rows = jdbc.queryForObject(
                "select count(*) from partner_mtls_cert where fingerprint_sha256 = ?",
                Integer.class, fingerprint);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void v028_checkRosters_fireOnPostgres() {
        Long partnerId = seededPartnerId();
        jdbc.update("insert into partner_credential (partner_id, environment,"
                + " credential_kind, prefix, issued_at, status)"
                + " values (?, 'SANDBOX', 'API_KEY', 'pk_test_', now(), 'ACTIVE')",
                partnerId);

        assertThatThrownBy(() -> jdbc.update("insert into partner_credential (partner_id,"
                        + " environment, credential_kind, prefix, issued_at, status)"
                        + " values (?, 'SANDBOX', 'PASSWORD', 'pk_test_', now(), 'ACTIVE')",
                partnerId))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update("insert into partner_credential (partner_id,"
                        + " environment, credential_kind, prefix, issued_at, status)"
                        + " values (?, 'SANDBOX', 'API_KEY', 'pk_test_', now(), 'BROKEN')",
                partnerId))
                .isInstanceOf(DataAccessException.class);
    }
}
