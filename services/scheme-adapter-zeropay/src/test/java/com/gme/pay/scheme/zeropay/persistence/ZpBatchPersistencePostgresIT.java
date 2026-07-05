package com.gme.pay.scheme.zeropay.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-PostgreSQL acceptance coverage for 17.2-G10: runs the Flyway migrations and
 * the full persistence contract (batch file registry + ZP record staging round-trips,
 * unique/FK constraints) against a postgres:16 Testcontainer.
 *
 * <p>{@code @Tag("docker")}: excluded from the local {@code test} task (this machine
 * has no Docker) and executed by the {@code integrationTest} task on CI ubuntu
 * runners. {@code disabledWithoutDocker = true} also self-skips defensively if ever
 * discovered on a Docker-less host. Test configuration ({@code @DataJpaTest} etc.)
 * is inherited from {@link AbstractZpBatchPersistenceContract}.</p>
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class ZpBatchPersistencePostgresIT extends AbstractZpBatchPersistenceContract {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @DynamicPropertySource
    static void postgresDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("All Flyway migrations apply cleanly on PostgreSQL 16 with stable checksums")
    void flywayMigrationsApplyOnPostgres16() throws Exception {
        String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
        assertNotNull(version);
        assertTrue(version.startsWith("PostgreSQL 16"), "expected PostgreSQL 16 but was: " + version);

        // Expected versions come from the classpath, so adding V00N never stales this test
        // (a hardcoded "exactly 2" broke the moment V003 landed).
        List<String> expectedVersions = expectedMigrationVersions();
        assertTrue(expectedVersions.size() >= 2, "sanity: migration scripts present on classpath");

        List<Map<String, Object>> applied = jdbcTemplate.queryForList(
                "SELECT version, checksum, success FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");
        assertEquals(expectedVersions,
                applied.stream().map(r -> String.valueOf(r.get("version"))).toList(),
                "applied migrations must match the V*.sql scripts on the classpath, in order");
        for (Map<String, Object> row : applied) {
            assertEquals(Boolean.TRUE, row.get("success"),
                    "migration V" + row.get("version") + " must apply successfully");
            assertNotNull(row.get("checksum"),
                    "migration V" + row.get("version") + " must record a checksum");
        }

        // All migrated tables exist in the public schema with PG-native types intact.
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_name IN ('zp_batch_files', 'zp_staged_records', 'zp_committed_txns')",
                Integer.class);
        assertEquals(3, tables);
    }

    /** Versions ("001", "002", …) of every {@code db/migration/V*__*.sql} on the classpath, sorted. */
    private static List<String> expectedMigrationVersions() throws Exception {
        org.springframework.core.io.Resource[] scripts =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources("classpath:db/migration/V*__*.sql");
        return java.util.Arrays.stream(scripts)
                .map(r -> r.getFilename().substring(1, r.getFilename().indexOf("__")))
                .sorted()
                .toList();
    }
}
