package com.gme.pay.settlement.persistence;

import com.gme.pay.settlement.calculator.GrossSettlementAmountCalculator;
import com.gme.pay.settlement.calculator.GrossSettlementSummary;
import com.gme.pay.settlement.calculator.NetSettlementAmountCalculator;
import com.gme.pay.settlement.calculator.NetSettlementSummary;
import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.recon.LineMatcher;
import com.gme.pay.settlement.recon.MatchStatus;
import com.gme.pay.settlement.recon.ReconLine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Real-PostgreSQL integration tests for the settlement-reconciliation persistence layer
 * (ticket 17.2-G07): Flyway migrations, settlement_batches / settlement_lines /
 * recon_exceptions round-trips, and BigDecimal NUMERIC(20,8) fidelity.
 *
 * <p>Runs against a Testcontainers {@code postgres:16} instance. Tagged {@code docker} so
 * the local (Docker-less) {@code test} task only compiles it; CI executes it via the
 * {@code integrationTest} task on Docker-capable runners.
 *
 * <p>NUMERIC fidelity contract verified here: PostgreSQL coerces stored values to the
 * declared column scale of 8 (e.g. {@code 99200} reads back as {@code 99200.00000000}) —
 * numerically identical ({@code compareTo == 0}), deterministic scale, and no precision
 * loss for full 8-decimal values. KRW business amounts are integer-scale BigDecimals
 * produced by the calculators; the padded read-back must never drift in value.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class SettlementReconciliationPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // application.yml pins the H2 dialect for the unit-slice default; override for PG.
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private SettlementBatchRepository batchRepository;

    @Autowired
    private SettlementLineRepository lineRepository;

    @Autowired
    private ReconExceptionRepository reconExceptionRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------------
    // Flyway migrations on real PostgreSQL
    // -------------------------------------------------------------------------------

    @Test
    void flywayMigrationsApplyCleanlyOnPostgres16() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, checksum, success FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");

        // V001..V003 are foundational; later slices add more, so assert the first three are present
        // in order rather than an exact total (which rots every time a migration is added).
        assertThat(history).hasSizeGreaterThanOrEqualTo(3);
        assertThat(history).extracting(row -> row.get("version"))
                .startsWith("001", "002", "003");
        assertThat(history).allSatisfy(row -> {
            assertThat((Boolean) row.get("success")).isTrue();
            assertThat(row.get("checksum")).isNotNull();
        });

        // All three owned tables exist with the expected NUMERIC(20,8) money columns.
        List<Map<String, Object>> moneyColumns = jdbcTemplate.queryForList(
                "SELECT table_name, column_name, numeric_precision, numeric_scale "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND data_type = 'numeric' "
                        + "ORDER BY table_name, column_name");

        assertThat(moneyColumns)
                .extracting(r -> r.get("table_name") + "." + r.get("column_name"))
                .containsExactly(
                        "recon_exceptions.discrepancy_amount",
                        "recon_exceptions.gme_amount",
                        "recon_exceptions.scheme_amount",
                        "settlement_batches.total_amount",
                        "settlement_lines.amount");
        assertThat(moneyColumns).allSatisfy(r -> {
            assertThat(((Number) r.get("numeric_precision")).intValue()).isEqualTo(20);
            assertThat(((Number) r.get("numeric_scale")).intValue()).isEqualTo(8);
        });
    }

    // -------------------------------------------------------------------------------
    // settlement_batches + settlement_lines round-trip
    // -------------------------------------------------------------------------------

    @Test
    void batchWithLines_roundTripsOnPostgres_withFullNumericPrecision() {
        // Full 8-decimal, near-max NUMERIC(20,8) value: 12 integer digits + 8 decimals.
        BigDecimal fullPrecision = new BigDecimal("123456789012.34567891");

        SettlementBatchEntity batch = new SettlementBatchEntity(
                "BATCH-PG-RT-001", "GME_REMIT", LocalDate.of(2026, 6, 10), "PENDING",
                fullPrecision, "KRW", Instant.parse("2026-06-10T02:00:00Z"));
        batchRepository.save(batch);

        lineRepository.save(new SettlementLineEntity(
                "BATCH-PG-RT-001", "TXN-001", new BigDecimal("0.00000001"), "KRW", false));
        lineRepository.save(new SettlementLineEntity(
                "BATCH-PG-RT-001", "TXN-002", new BigDecimal("123456789012.34567890"), "KRW", true));

        entityManager.flush();
        entityManager.clear(); // force re-read from PostgreSQL, not the L1 cache

        SettlementBatchEntity reloaded = batchRepository.findById("BATCH-PG-RT-001").orElseThrow();
        // Exact representation back: PG NUMERIC(20,8) preserves all 8 decimals, no drift.
        assertThat(reloaded.getTotalAmount()).isEqualTo(fullPrecision);
        assertThat(reloaded.getTotalAmount().scale()).isEqualTo(8);
        assertThat(reloaded.getPartnerId()).isEqualTo("GME_REMIT");
        assertThat(reloaded.getBusinessDate()).isEqualTo(LocalDate.of(2026, 6, 10));

        List<SettlementLineEntity> lines = lineRepository.findByBatchId("BATCH-PG-RT-001");
        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(SettlementLineEntity::getAmount)
                .containsExactlyInAnyOrder(
                        new BigDecimal("0.00000001"),
                        new BigDecimal("123456789012.34567890"));
        assertThat(lineRepository.findByBatchIdAndMatched("BATCH-PG-RT-001", true))
                .singleElement()
                .satisfies(l -> assertThat(l.getTxnRef()).isEqualTo("TXN-002"));
    }

    // -------------------------------------------------------------------------------
    // NET calculation result persisted and re-read with NUMERIC fidelity
    // -------------------------------------------------------------------------------

    @Test
    void netSettlementCalcResult_persistsAndRereadsWithoutScaleDrift() {
        NetSettlementAmountCalculator calculator = new NetSettlementAmountCalculator();
        List<TransactionRecord> txns = List.of(
                netTxn(1L, "TXN-N-1", new BigDecimal("60000"), new BigDecimal("0.008")),
                netTxn(2L, "TXN-N-2", new BigDecimal("40000"), new BigDecimal("0.008")),
                netTxn(3L, "TXN-N-3", new BigDecimal("33333"), new BigDecimal("0.0125")));

        NetSettlementSummary summary =
                calculator.calculate("MERCH-NET-1", LocalDate.of(2026, 6, 10), txns);
        // fees: 480 + 320 + ROUND(416.6625) = 480 + 320 + 417 = 1217; net = 133333 - 1217
        assertThat(summary.netSettlementAmount()).isEqualByComparingTo("132116");

        SettlementBatchEntity batch = new SettlementBatchEntity(
                "BATCH-PG-NET-001", "MERCH-NET-1", summary.settlementDate(), "GENERATED",
                summary.netSettlementAmount(), "KRW", Instant.parse("2026-06-10T04:30:00Z"));
        batchRepository.save(batch);
        for (TransactionRecord txn : txns) {
            lineRepository.save(new SettlementLineEntity(
                    "BATCH-PG-NET-001", txn.txnRef(), txn.targetPayoutKrw(), "KRW", false));
        }

        entityManager.flush();
        entityManager.clear();

        SettlementBatchEntity reloaded = batchRepository.findById("BATCH-PG-NET-001").orElseThrow();
        // No value drift vs the in-memory calculator result...
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo(summary.netSettlementAmount());
        // ...and a deterministic scale-8 NUMERIC representation (no rounding, only padding).
        assertThat(reloaded.getTotalAmount()).isEqualTo(new BigDecimal("132116.00000000"));

        BigDecimal lineSum = lineRepository.findByBatchId("BATCH-PG-NET-001").stream()
                .map(SettlementLineEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineSum).isEqualByComparingTo(summary.grossTxnAmount());
    }

    // -------------------------------------------------------------------------------
    // GROSS calculation result persisted and re-read with NUMERIC fidelity
    // -------------------------------------------------------------------------------

    @Test
    void grossSettlementCalcResult_persistsAndRereadsWithoutScaleDrift() {
        GrossSettlementAmountCalculator calculator = new GrossSettlementAmountCalculator();
        List<TransactionRecord> txns = List.of(
                grossTxn(10L, "TXN-G-1", new BigDecimal("5000000")),
                grossTxn(11L, "TXN-G-2", new BigDecimal("2500000")));

        GrossSettlementSummary summary =
                calculator.calculate("MERCH-GROSS-1", LocalDate.of(2026, 6, 10), txns);
        assertThat(summary.netSettlementAmount()).isEqualByComparingTo("7500000");
        assertThat(summary.merchantFeeTotal()).isEqualByComparingTo(BigDecimal.ZERO);

        SettlementBatchEntity batch = new SettlementBatchEntity(
                "BATCH-PG-GROSS-001", "MERCH-GROSS-1", summary.settlementDate(), "GENERATED",
                summary.netSettlementAmount(), "KRW", Instant.parse("2026-06-10T13:30:00Z"));
        batchRepository.save(batch);

        entityManager.flush();
        entityManager.clear();

        SettlementBatchEntity reloaded = batchRepository.findById("BATCH-PG-GROSS-001").orElseThrow();
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo(summary.grossTxnAmount());
        assertThat(reloaded.getTotalAmount()).isEqualTo(new BigDecimal("7500000.00000000"));
        assertThat(reloaded.getTotalAmount().scale()).isEqualTo(8);
    }

    // -------------------------------------------------------------------------------
    // LineMatcher results round-trip through recon_exceptions
    // -------------------------------------------------------------------------------

    @Test
    void lineMatcherResults_roundTripThroughReconExceptionsTable() {
        LineMatcher matcher = new LineMatcher();
        Map<String, BigDecimal> gme = Map.of(
                "MERCH-A", new BigDecimal("10000"),
                "MERCH-B", new BigDecimal("25000"),
                "MERCH-C", new BigDecimal("7000"));
        Map<String, BigDecimal> scheme = Map.of(
                "MERCH-A", new BigDecimal("10000"),
                "MERCH-B", new BigDecimal("24000"),
                "MERCH-D", new BigDecimal("3000"));

        List<ReconLine> original = matcher.match(gme, scheme);
        assertThat(original).hasSize(4);

        Instant now = Instant.parse("2026-06-10T05:00:00Z");
        for (ReconLine line : original) {
            reconExceptionRepository.save(
                    ReconExceptionEntity.fromReconLine("BATCH-PG-RECON-001", line, now));
        }

        entityManager.flush();
        entityManager.clear();

        List<ReconExceptionEntity> persisted =
                reconExceptionRepository.findByBatchId("BATCH-PG-RECON-001");
        assertThat(persisted).hasSize(4);

        Map<String, ReconLine> reloadedByMerchant = persisted.stream()
                .map(ReconExceptionEntity::toReconLine)
                .collect(java.util.stream.Collectors.toMap(ReconLine::merchantId, l -> l));

        for (ReconLine expected : original) {
            ReconLine actual = reloadedByMerchant.get(expected.merchantId());
            assertThat(actual).as("merchant %s", expected.merchantId()).isNotNull();
            assertThat(actual.matchStatus()).isEqualTo(expected.matchStatus());
            assertThat(actual.gmeAmount()).isEqualByComparingTo(expected.gmeAmount());
            assertThat(actual.discrepancyAmount()).isEqualByComparingTo(expected.discrepancyAmount());
            if (expected.schemeAmount() == null) {
                assertThat(actual.schemeAmount()).isNull();
            } else {
                assertThat(actual.schemeAmount()).isEqualByComparingTo(expected.schemeAmount());
            }
            assertThat(actual.requiresAttention()).isEqualTo(expected.requiresAttention());
        }

        // Status-specific expectations survive the round-trip.
        assertThat(reloadedByMerchant.get("MERCH-A").matchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(reloadedByMerchant.get("MERCH-B").matchStatus()).isEqualTo(MatchStatus.DISCREPANCY);
        assertThat(reloadedByMerchant.get("MERCH-B").discrepancyAmount()).isEqualByComparingTo("1000");
        assertThat(reloadedByMerchant.get("MERCH-C").matchStatus()).isEqualTo(MatchStatus.MISSING_SCHEME);
        assertThat(reloadedByMerchant.get("MERCH-C").schemeAmount()).isNull();
        assertThat(reloadedByMerchant.get("MERCH-D").matchStatus()).isEqualTo(MatchStatus.MISSING_INTERNAL);
        assertThat(reloadedByMerchant.get("MERCH-D").gmeAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // Repository filters work against the PG enum-as-varchar column.
        assertThat(reconExceptionRepository
                .findByBatchIdAndMatchStatus("BATCH-PG-RECON-001", MatchStatus.DISCREPANCY))
                .singleElement()
                .satisfies(e -> assertThat(e.getMerchantId()).isEqualTo("MERCH-B"));
        assertThat(reconExceptionRepository
                .countByBatchIdAndMatchStatusNot("BATCH-PG-RECON-001", MatchStatus.MATCHED))
                .isEqualTo(3);
    }

    @Test
    void reconExceptionAmounts_keepFullEightDecimalPrecisionOnPostgres() {
        BigDecimal maxPrecision = new BigDecimal("999999999999.99999999");
        BigDecimal tiny = new BigDecimal("0.00000001");

        ReconExceptionEntity row = new ReconExceptionEntity(
                "BATCH-PG-RECON-002", "MERCH-PRECISION",
                maxPrecision, tiny, maxPrecision.subtract(tiny),
                MatchStatus.DISCREPANCY, Instant.parse("2026-06-10T05:10:00Z"));
        reconExceptionRepository.save(row);

        entityManager.flush();
        entityManager.clear();

        ReconExceptionEntity reloaded =
                reconExceptionRepository.findByBatchId("BATCH-PG-RECON-002").get(0);
        assertThat(reloaded.getGmeAmount()).isEqualTo(maxPrecision);
        assertThat(reloaded.getSchemeAmount()).isEqualTo(tiny);
        assertThat(reloaded.getDiscrepancyAmount()).isEqualTo(new BigDecimal("999999999999.99999998"));
        assertThat(reloaded.getGmeAmount().scale()).isEqualTo(8);
    }

    // -------------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------------

    private static TransactionRecord netTxn(Long id, String txnRef, BigDecimal payoutKrw, BigDecimal feeRate) {
        return new TransactionRecord(id, txnRef, "ZP-" + txnRef, "MERCH-NET-1",
                payoutKrw, 'N', feeRate, "APPROVED", null, null);
    }

    private static TransactionRecord grossTxn(Long id, String txnRef, BigDecimal payoutKrw) {
        return new TransactionRecord(id, txnRef, "ZP-" + txnRef, "MERCH-GROSS-1",
                payoutKrw, 'G', BigDecimal.ZERO, "APPROVED", null, null);
    }
}
