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
    @DisplayName("Flyway migrations V001+V002 apply cleanly on PostgreSQL 16 with stable checksums")
    void flywayMigrationsApplyOnPostgres16() {
        String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
        assertNotNull(version);
        assertTrue(version.startsWith("PostgreSQL 16"), "expected PostgreSQL 16 but was: " + version);

        List<Map<String, Object>> applied = jdbcTemplate.queryForList(
                "SELECT version, checksum, success FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");
        assertEquals(2, applied.size(), "expected exactly V001 and V002");
        assertEquals("001", applied.get(0).get("version"));
        assertEquals("002", applied.get(1).get("version"));
        for (Map<String, Object> row : applied) {
            assertEquals(Boolean.TRUE, row.get("success"),
                    "migration V" + row.get("version") + " must apply successfully");
            assertNotNull(row.get("checksum"),
                    "migration V" + row.get("version") + " must record a checksum");
        }

        // Both tables exist in the public schema with PG-native types intact.
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_name IN ('zp_batch_files', 'zp_staged_records')",
                Integer.class);
        assertEquals(2, tables);
    }
}
