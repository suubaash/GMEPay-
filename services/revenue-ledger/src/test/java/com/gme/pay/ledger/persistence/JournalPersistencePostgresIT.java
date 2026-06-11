package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import com.gme.pay.ledger.outbox.OutboxWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Real-PostgreSQL acceptance coverage for <b>17.2-G06</b>: the unit-slice tests keep H2 in
 * PostgreSQL mode, but the migration + double-entry persistence contract must also hold
 * against a genuine postgres:16 — this IT runs Flyway {@code V001..V003} and the
 * {@link LedgerPostingService} round-trips on a Testcontainer.
 *
 * <p>{@code @Tag("docker")}: excluded from the local {@code test} task (this machine has no
 * Docker) and executed by the {@code integrationTest} task on CI ubuntu runners.
 * {@code disabledWithoutDocker = true} also self-skips defensively if ever discovered on a
 * Docker-less host.</p>
 *
 * <p>Covers:</p>
 * <ol>
 *   <li>Flyway V001 (journals) + V002 (ledger_entries) + V003 (outbox) migrate cleanly with
 *       stable checksums and no PG-syntax drift;</li>
 *   <li>a posted journal round-trips and satisfies the double-entry invariant — verified with
 *       a SQL aggregate (per journal+currency: SUM(debits) = SUM(credits)) on the real PG;</li>
 *   <li>{@code postRoundingResidual} persists a REVENUE_ROUNDING gain (residual &gt; 0) /
 *       loss (residual &lt; 0) correctly, and a zero residual returns {@code null} and
 *       persists nothing.</li>
 * </ol>
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({JpaJournalStore.class, InMemoryJournalStore.class, LedgerPostingService.class,
        SchemeFeeSplitCalculator.class, OutboxWriter.class})
class JournalPersistencePostgresIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @DynamicPropertySource
    static void postgresDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // application.properties pins the H2 dialect for the no-docker unit slices; override for PG.
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private JpaJournalStore jpaJournalStore;

    @Autowired
    private JournalEntityRepository journalRepo;

    @Autowired
    private LedgerEntryEntityRepository entryRepo;

    // ------------------------------------------------------------------
    // 1. Migrations V001..V003 on real PostgreSQL 16
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Flyway V001..V003 apply cleanly on PostgreSQL 16 with stable checksums")
    void flywayMigrationsApplyOnPostgres16() {
        String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
        assertNotNull(version);
        assertTrue(version.startsWith("PostgreSQL 16"), "expected PostgreSQL 16 but was: " + version);

        List<Map<String, Object>> applied = jdbcTemplate.queryForList(
                "SELECT version, checksum, success FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");
        assertEquals(3, applied.size(), "expected exactly V001, V002 and V003");
        assertEquals("001", applied.get(0).get("version"));
        assertEquals("002", applied.get(1).get("version"));
        assertEquals("003", applied.get(2).get("version"));
        for (Map<String, Object> row : applied) {
            assertEquals(Boolean.TRUE, row.get("success"),
                    "migration V" + row.get("version") + " must apply successfully");
            assertNotNull(row.get("checksum"),
                    "migration V" + row.get("version") + " must record a checksum");
        }

        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_name IN ('journals', 'ledger_entries', 'outbox')",
                Integer.class);
        assertEquals(3, tables, "journals, ledger_entries and outbox must all exist");

        // amount must be the PG-native NUMERIC(20,8) — no H2 type drift.
        Map<String, Object> amountCol = jdbcTemplate.queryForMap(
                "SELECT data_type, numeric_precision, numeric_scale FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'ledger_entries' AND column_name = 'amount'");
        assertEquals("numeric", amountCol.get("data_type"));
        assertEquals(20, ((Number) amountCol.get("numeric_precision")).intValue());
        assertEquals(8, ((Number) amountCol.get("numeric_scale")).intValue());
    }

    // ------------------------------------------------------------------
    // 2. Double-entry posting round-trip + SQL invariant on real PG
    // ------------------------------------------------------------------

    @Test
    @DisplayName("posted journal round-trips on PG and satisfies the debits=credits SQL invariant")
    void doubleEntryPostingRoundTrip_balancesOnPostgres() {
        String ref = "TXN-PG-CAP-" + UUID.randomUUID();

        Journal posted = ledgerPostingService.postRevenueCapture(
                ref, new BigDecimal("12.3400"), new BigDecimal("500"), "KRW");
        entityManager.flush(); // make the JPA writes visible to the plain-SQL invariant query

        // Round-trip through the store: id, postedAt, and all 4 lines preserved.
        Journal reread = jpaJournalStore.findById(posted.journalId()).orElseThrow(
                () -> new AssertionError("journal " + posted.journalId() + " must be readable back from PG"));
        assertEquals(posted.journalId(), reread.journalId());
        assertEquals(4, reread.entries().size(),
                "2 USD lines (fx margin) + 2 KRW lines (service charge) expected");

        // Double-entry invariant, asserted in SQL against the real PG (not in-memory):
        // per (journal_id, currency) the debit total must equal the credit total.
        Integer unbalancedGroups = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ("
                        + "  SELECT journal_id, currency,"
                        + "         SUM(CASE WHEN entry_type = 'DEBIT'  THEN amount ELSE 0 END) AS debits,"
                        + "         SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END) AS credits"
                        + "  FROM ledger_entries GROUP BY journal_id, currency"
                        + ") balance WHERE debits <> credits",
                Integer.class);
        assertEquals(0, unbalancedGroups,
                "every (journal, currency) group must balance: SUM(debits) = SUM(credits)");

        // And the invariant is non-vacuous: our journal contributed rows to the aggregate.
        Integer ourRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE journal_id = ?",
                Integer.class, posted.journalId());
        assertEquals(4, ourRows);
    }

    // ------------------------------------------------------------------
    // 3. Rounding residual: gain / loss / zero on real PG
    // ------------------------------------------------------------------

    @Test
    @DisplayName("postRoundingResidual residual>0 persists a REVENUE_ROUNDING gain (credit) on PG")
    void postRoundingResidual_gain_persistsCreditOnPostgres() {
        String ref = "TXN-PG-ROUND-GAIN-" + UUID.randomUUID();

        Journal journal = ledgerPostingService.postRoundingResidual(ref, new BigDecimal("0.007"), "USD");
        assertNotNull(journal, "positive residual must post a journal");
        entityManager.flush();

        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
                "SELECT account, amount, currency, entry_type FROM ledger_entries "
                        + "WHERE journal_id = ? ORDER BY id", journal.journalId());
        assertEquals(2, lines.size(), "rounding-gain journal has exactly 2 lines");

        assertEquals(1, lines.stream().filter(l ->
                        ChartOfAccounts.REVENUE_ROUNDING.equals(l.get("account"))
                                && "CREDIT".equals(l.get("entry_type"))
                                && "USD".equals(l.get("currency"))
                                && new BigDecimal("0.007").compareTo((BigDecimal) l.get("amount")) == 0).count(),
                "gain: REVENUE_ROUNDING must be CREDITED 0.007 USD; rows=" + lines);
        assertEquals(1, lines.stream().filter(l ->
                        ChartOfAccounts.RECEIVABLE_PARTNER.equals(l.get("account"))
                                && "DEBIT".equals(l.get("entry_type"))
                                && new BigDecimal("0.007").compareTo((BigDecimal) l.get("amount")) == 0).count(),
                "gain: RECEIVABLE_PARTNER must be DEBITED 0.007 USD; rows=" + lines);
    }

    @Test
    @DisplayName("postRoundingResidual residual<0 persists a REVENUE_ROUNDING loss (debit) on PG")
    void postRoundingResidual_loss_persistsDebitOnPostgres() {
        String ref = "TXN-PG-ROUND-LOSS-" + UUID.randomUUID();

        Journal journal = ledgerPostingService.postRoundingResidual(ref, new BigDecimal("-0.003"), "USD");
        assertNotNull(journal, "negative residual must post a journal");
        entityManager.flush();

        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
                "SELECT account, amount, currency, entry_type FROM ledger_entries "
                        + "WHERE journal_id = ? ORDER BY id", journal.journalId());
        assertEquals(2, lines.size(), "rounding-loss journal has exactly 2 lines");

        assertEquals(1, lines.stream().filter(l ->
                        ChartOfAccounts.REVENUE_ROUNDING.equals(l.get("account"))
                                && "DEBIT".equals(l.get("entry_type"))
                                && "USD".equals(l.get("currency"))
                                && new BigDecimal("0.003").compareTo((BigDecimal) l.get("amount")) == 0).count(),
                "loss: REVENUE_ROUNDING must be DEBITED 0.003 USD; rows=" + lines);
        assertEquals(1, lines.stream().filter(l ->
                        ChartOfAccounts.RECEIVABLE_PARTNER.equals(l.get("account"))
                                && "CREDIT".equals(l.get("entry_type"))
                                && new BigDecimal("0.003").compareTo((BigDecimal) l.get("amount")) == 0).count(),
                "loss: RECEIVABLE_PARTNER must be CREDITED 0.003 USD; rows=" + lines);
    }

    @Test
    @DisplayName("postRoundingResidual residual=0 returns null and persists nothing")
    void postRoundingResidual_zero_returnsNullAndPostsNothing() {
        String ref = "TXN-PG-ROUND-ZERO-" + UUID.randomUUID();
        long journalsBefore = journalRepo.count();

        Journal journal = ledgerPostingService.postRoundingResidual(ref, BigDecimal.ZERO, "USD");

        assertNull(journal, "zero residual must not mint a journal");
        entityManager.flush();
        assertEquals(journalsBefore, journalRepo.count(), "no journal row may be added");
        assertTrue(entryRepo.findByReferenceOrderByIdAsc(ref).isEmpty(),
                "no ledger_entries rows may be written for the reference");
    }
}
