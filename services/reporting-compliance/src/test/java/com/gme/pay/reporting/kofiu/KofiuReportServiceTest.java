package com.gme.pay.reporting.kofiu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link KofiuReportService}.
 *
 * <p>All three ports (transaction, regulatory config, corridor config) are
 * stubbed with hand-rolled lambdas — no Spring context, no Mockito required.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>CTR: just-under threshold does NOT trigger a report.</li>
 *   <li>CTR: at-threshold (==) triggers a report (inclusive).</li>
 *   <li>CTR: above threshold triggers a report.</li>
 *   <li>CTR: aggregation is per end-user across multiple transactions.</li>
 *   <li>STR: corridor with str_enabled=false does NOT trigger.</li>
 *   <li>STR: corridor with str_enabled=true triggers a per-transaction report.</li>
 *   <li>STR: only transactions on the flagged corridor are included.</li>
 *   <li>Empty transaction list produces empty batch.</li>
 * </ul>
 */
class KofiuReportServiceTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 6, 15);

    /** UTC instant that falls on 2026-06-15 in KST (Asia/Seoul = UTC+9). */
    private static final Instant KST_DATE_INSTANT =
            Instant.parse("2026-06-15T00:30:00Z"); // 09:30 KST

    private static final BigDecimal THRESHOLD = new BigDecimal("10000000");

    // =========================================================================
    // TEST 1: just-under threshold — no CTR
    // =========================================================================

    @Test
    @DisplayName("CTR: daily total just under threshold does NOT trigger a report")
    void ctr_justUnder_doesNotTrigger() {
        BigDecimal justUnder = new BigDecimal("9999999.99"); // 1 jeon below 10M KRW

        KofiuTransaction txn = txn("USER-1", 1L, justUnder, "KRW", "USD");
        KofiuReportService service = serviceWithFixedThreshold(THRESHOLD, List.of(txn));

        KofiuReportBatch batch = service.buildDailyBatch(REPORT_DATE);

        assertTrue(batch.getCtrReports().isEmpty(),
                "Expected no CTR for aggregate " + justUnder + " < threshold " + THRESHOLD);
    }

    // =========================================================================
    // TEST 2: exactly at threshold — CTR triggers (inclusive)
    // =========================================================================

    @Test
    @DisplayName("CTR: daily total exactly at threshold triggers a report (inclusive >=)")
    void ctr_atThreshold_triggers() {
        KofiuTransaction txn = txn("USER-2", 1L, THRESHOLD, "KRW", "USD");
        KofiuReportService service = serviceWithFixedThreshold(THRESHOLD, List.of(txn));

        KofiuReportBatch batch = service.buildDailyBatch(REPORT_DATE);

        assertEquals(1, batch.getCtrReports().size(),
                "Expected 1 CTR for aggregate == threshold");
        CtrReport ctr = batch.getCtrReports().get(0);
        assertEquals("USER-2", ctr.getEndUserId());
        assertEquals(0, THRESHOLD.compareTo(ctr.getTotalAmountKrw()));
        assertEquals(1, ctr.getTransactionCount());
    }

    // =========================================================================
    // TEST 3: above threshold — CTR triggers
    // =========================================================================

    @Test
    @DisplayName("CTR: daily total above threshold triggers a report")
    void ctr_aboveThreshold_triggers() {
        BigDecimal over = new BigDecimal("15000000");
        KofiuTransaction txn = txn("USER-3", 1L, over, "KRW", "USD");
        KofiuReportService service = serviceWithFixedThreshold(THRESHOLD, List.of(txn));

        KofiuReportBatch batch = service.buildDailyBatch(REPORT_DATE);

        assertEquals(1, batch.getCtrReports().size());
        assertEquals(0, over.compareTo(batch.getCtrReports().get(0).getTotalAmountKrw()));
    }

    // =========================================================================
    // TEST 4: multi-transaction per end-user — amounts are aggregated
    // =========================================================================

    @Test
    @DisplayName("CTR: multiple transactions for one end-user are aggregated before threshold check")
    void ctr_multiTxn_aggregated() {
        // Three transactions: 4M + 4M + 3M = 11M  → over 10M threshold
        KofiuTransaction t1 = txn("USER-4", 1L, new BigDecimal("4000000"), "KRW", "USD");
        KofiuTransaction t2 = txn("USER-4", 1L, new BigDecimal("4000000"), "KRW", "USD");
        KofiuTransaction t3 = txn("USER-4", 1L, new BigDecimal("3000000"), "KRW", "USD");
        KofiuReportService service = serviceWithFixedThreshold(THRESHOLD,
                List.of(t1, t2, t3));

        KofiuReportBatch batch = service.buildDailyBatch(REPORT_DATE);

        assertEquals(1, batch.getCtrReports().size());
        CtrReport ctr = batch.getCtrReports().get(0);
        assertEquals(0, new BigDecimal("11000000").compareTo(ctr.getTotalAmountKrw()));
        assertEquals(3, ctr.getTransactionCount());
        assertEquals(3, ctr.getContributingTxnIds().size());
    }

    // =========================================================================
    // TEST 5: two different end-users — only the one over threshold gets a CTR
    // =========================================================================

    @Test
    @DisplayName("CTR: aggregation is per-end-user; only users crossing threshold are reported")
    void ctr_perEndUser_onlyOverThresholdReported() {
        KofiuTransaction user5over = txn("USER-5-OVER", 1L, new BigDecimal("12000000"), "KRW", "USD");
        KofiuTransaction user5under = txn("USER-5-UNDER", 1L, new BigDecimal("5000000"), "KRW", "USD");
        KofiuReportService service = serviceWithFixedThreshold(THRESHOLD,
                List.of(user5over, user5under));

        KofiuReportBatch batch = service.buildDailyBatch(REPORT_DATE);

        assertEquals(1, batch.getCtrReports().size());
        assertEquals("USER-5-OVER", batch.getCtrReports().get(0).getEndUserId());
    }

    // =========================================================================
    // TEST 6: STR — corridor str_enabled=false → no STR
    // =========================================================================

    @Test
    @DisplayName("STR: corridor with str_enabled=false does not trigger an STR")
    void str_corridorFlagOff_noReport() {
        KofiuTransaction txn = txn("USER-6", 1L, new BigDecimal("500000"), "KRW", "USD");
        // str_enabled=false for all corridors
        KofiuReportService service = serviceWithStrEnabled(false, List.of(txn));

        KofiuReportBatch batch = service.buildDailyBatch(REPORT_DATE);

        assertTrue(batch.getStrReports().isEmpty(),
                "Expected no STR when corridor str_enabled=false");
    }

    // =========================================================================
    // TEST 7: STR — corridor str_enabled=true → per-transaction STR
    // =========================================================================

    @Test
    @DisplayName("STR: corridor with str_enabled=true triggers one STR per transaction")
    void str_corridorFlagOn_triggersPerTransaction() {
        KofiuTransaction t1 = txn("USER-7", 1L, new BigDecimal("500000"), "KRW", "PHP");
        KofiuTransaction t2 = txn("USER-7", 1L, new BigDecimal("300000"), "KRW", "PHP");
        KofiuReportService service = serviceWithStrEnabled(true, List.of(t1, t2));

        KofiuReportBatch batch = service.buildDailyBatch(REPORT_DATE);

        assertEquals(2, batch.getStrReports().size(),
                "Expected one STR per transaction on str_enabled corridor");
        for (StrReport str : batch.getStrReports()) {
            assertEquals("KRW", str.getSrcCcy());
            assertEquals("PHP", str.getDstCcy());
            assertEquals(REPORT_DATE, str.getReportDate());
        }
    }

    // =========================================================================
    // TEST 8: STR — only the flagged corridor gets STRs; others are silent
    // =========================================================================

    @Test
    @DisplayName("STR: only transactions on the str_enabled corridor are reported")
    void str_onlyFlaggedCorridorReported() {
        KofiuTransaction flaggedCorridor = txn("USER-8", 1L,
                new BigDecimal("200000"), "KRW", "PHP");
        KofiuTransaction normalCorridor = txn("USER-8", 1L,
                new BigDecimal("200000"), "KRW", "USD");

        // Only KRW->PHP is flagged; KRW->USD is not
        CorridorConfigPort corridorPort = (partnerId, srcCcy, dstCcy) ->
                "PHP".equals(dstCcy);
        RegulatoryConfigPort regPort = regulatoryPort(THRESHOLD);

        KofiuReportService service = new KofiuReportService(
                (from, to) -> List.of(flaggedCorridor, normalCorridor),
                regPort,
                corridorPort);

        KofiuReportBatch batch = service.buildDailyBatch(REPORT_DATE);

        assertEquals(1, batch.getStrReports().size());
        assertEquals("PHP", batch.getStrReports().get(0).getDstCcy());
    }

    // =========================================================================
    // TEST 9: empty transaction list → empty batch
    // =========================================================================

    @Test
    @DisplayName("Empty transaction list produces an empty batch")
    void emptyTransactions_emptyBatch() {
        KofiuReportService service = new KofiuReportService(
                (from, to) -> List.of(),
                regulatoryPort(THRESHOLD),
                (partnerId, src, dst) -> false);

        KofiuReportBatch batch = service.buildDailyBatch(REPORT_DATE);

        assertTrue(batch.isEmpty());
        assertEquals(0, batch.totalReports());
    }

    // =========================================================================
    // TEST 10: null reportDate throws
    // =========================================================================

    @Test
    @DisplayName("buildDailyBatch with null reportDate throws NullPointerException")
    void nullReportDate_throws() {
        KofiuReportService service = new KofiuReportService(
                (from, to) -> List.of(),
                regulatoryPort(THRESHOLD),
                (partnerId, src, dst) -> false);

        assertThrows(NullPointerException.class,
                () -> service.buildDailyBatch(null));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int txnCounter = 1000;

    private static KofiuTransaction txn(
            String endUserId, long partnerId, BigDecimal amount,
            String srcCcy, String dstCcy) {
        long id = txnCounter++;
        return new KofiuTransaction(
                id,
                "REF-" + id,
                endUserId,
                amount,
                srcCcy,
                dstCcy,
                partnerId,
                KST_DATE_INSTANT);
    }

    /** Creates a {@link RegulatoryConfigPort} stub that always returns {@code threshold}. */
    private static RegulatoryConfigPort regulatoryPort(BigDecimal threshold) {
        return new RegulatoryConfigPort() {
            @Override
            public Optional<BigDecimal> findCtrThreshold(long partnerId) {
                return Optional.of(threshold);
            }
            @Override
            public Optional<String> findKofiuEntityId(long partnerId) {
                return Optional.empty();
            }
        };
    }

    /**
     * Builds a service where ALL corridors return the same {@code strEnabled}
     * value and the regulatory config returns the given threshold.
     */
    private KofiuReportService serviceWithStrEnabled(
            boolean strEnabled, List<KofiuTransaction> txns) {
        return new KofiuReportService(
                (from, to) -> txns,
                regulatoryPort(THRESHOLD),
                (partnerId, srcCcy, dstCcy) -> strEnabled);
    }

    /**
     * Builds a service with a fixed threshold and str disabled for all corridors.
     */
    private KofiuReportService serviceWithFixedThreshold(
            BigDecimal threshold, List<KofiuTransaction> txns) {
        return new KofiuReportService(
                (from, to) -> txns,
                regulatoryPort(threshold),
                (partnerId, srcCcy, dstCcy) -> false);
    }
}
