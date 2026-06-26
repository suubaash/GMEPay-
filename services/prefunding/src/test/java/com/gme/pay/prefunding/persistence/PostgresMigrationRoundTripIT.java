package com.gme.pay.prefunding.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Docker-backed IT: runs the Flyway migrations against a real PostgreSQL 16 container and verifies
 * the double-entry ledger schema round-trips through the JPA repositories with full NUMERIC(20,8)
 * precision. Excluded from the plain `test` task (tag "docker"); CI runs it via `integrationTest`.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PostgresMigrationRoundTripIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private PartnerBalanceRepository balances;

    @Autowired
    private LedgerEntryRepository ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        ledger.deleteAll();
        balances.deleteAll();
    }

    @Test
    void flywayMigrationsApplyCleanlyOnPostgres16() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, checksum, success FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");

        // V001/V002 are the foundational migrations; later slices add more (V003..V006+), so assert
        // the first two are present in order rather than an exact total (which rots every migration).
        assertTrue(history.size() >= 2, "expected at least V001 and V002 to be applied");
        assertEquals(1L, Long.parseLong((String) history.get(0).get("version")));
        assertEquals(2L, Long.parseLong((String) history.get(1).get("version")));
        for (Map<String, Object> row : history) {
            assertTrue((Boolean) row.get("success"), "migration must succeed: " + row);
            assertNotNull(row.get("checksum"), "checksum must be recorded: " + row);
        }

        // Both tables exist with their PG types intact (no H2-mode workarounds).
        Long tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_name IN ('partner_balance', 'ledger_entry')", Long.class);
        assertEquals(2L, tables);
        String balanceType = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'partner_balance' AND column_name = 'balance'",
                String.class);
        assertEquals("numeric", balanceType);
    }

    @Test
    void doubleEntryLedgerRoundTripsWithFullNumericPrecision() {
        balances.save(new PartnerBalanceEntity(
                "PG_RT", "USD",
                new BigDecimal("12345.67890123"),
                new BigDecimal("100.00000000"),
                Instant.now()));

        Optional<PartnerBalanceEntity> found = balances.findById("PG_RT");
        assertTrue(found.isPresent());
        // NUMERIC(20,8) must round-trip exactly, all 8 decimal places included.
        assertEquals(0, found.get().getBalance().compareTo(new BigDecimal("12345.67890123")));
        assertEquals("USD", found.get().getCurrency());

        Instant t0 = Instant.parse("2026-06-01T00:00:00Z");
        ledger.save(new LedgerEntryEntity("PG_RT", "tx-pg-1", "DEBIT",
                new BigDecimal("36.97140001"), "USD", t0));
        ledger.save(new LedgerEntryEntity("PG_RT", null, "CREDIT",
                new BigDecimal("500.00000000"), "USD", t0.plusSeconds(60)));

        List<LedgerEntryEntity> rows = ledger.findByPartnerIdOrderByCreatedAtAscIdAsc("PG_RT");
        assertEquals(2, rows.size());
        assertNotNull(rows.get(0).getId(), "BIGSERIAL id must be generated by PostgreSQL");
        assertNotNull(rows.get(1).getId());
        assertTrue(rows.get(1).getId() > rows.get(0).getId());
        assertEquals("DEBIT", rows.get(0).getEntryType());
        assertEquals("tx-pg-1", rows.get(0).getTxnRef());
        assertEquals(0, rows.get(0).getAmount().compareTo(new BigDecimal("36.97140001")));
        assertEquals("CREDIT", rows.get(1).getEntryType());
        assertEquals(0, rows.get(1).getAmount().compareTo(new BigDecimal("500.00000000")));
        assertEquals(2L, ledger.countByPartnerId("PG_RT"));
    }
}
