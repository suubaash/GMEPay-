package com.gme.pay.settlement.persistence;

import com.gme.pay.settlement.batch.SettlementBatchStatus;
import com.gme.pay.settlement.parser.ZP0062Parser;
import com.gme.pay.settlement.parser.ZeroPayResultRecord;
import com.gme.pay.settlement.recon.LineMatcher;
import com.gme.pay.settlement.recon.MatchStatus;
import com.gme.pay.settlement.recon.ReconDiffEngine;
import com.gme.pay.settlement.recon.ReconLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Pure unit-slice persistence test on H2 (PostgreSQL mode), migrated by Flyway, verifying
 * the JPA mappings for settlement_batches, settlement_lines and recon_exceptions compile
 * and round-trip without a Docker engine.
 *
 * <p>Real-PostgreSQL coverage (NUMERIC fidelity, PG-only syntax) lives in
 * {@link SettlementReconciliationPostgresIT}, which is tagged {@code docker} and runs in CI
 * via the {@code integrationTest} task (ticket 17.2-G07).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class SettlementPersistenceIT {

    @Autowired
    private SettlementBatchRepository batchRepository;

    @Autowired
    private SettlementLineRepository lineRepository;

    @Autowired
    private ReconExceptionRepository reconExceptionRepository;

    @Test
    void createAndSaveBatchWithLines_roundTrip() {
        SettlementBatchEntity batch = new SettlementBatchEntity(
                "BATCH-2026-06-09-LOCAL",
                "GME_REMIT",
                LocalDate.of(2026, 6, 9),
                "PENDING",
                new BigDecimal("100000.00000000"),
                "KRW",
                Instant.parse("2026-06-09T02:00:00Z"));
        batchRepository.save(batch);

        SettlementLineEntity l1 = new SettlementLineEntity(
                batch.getBatchId(), "TXN-001", new BigDecimal("60000.00000000"), "KRW", false);
        SettlementLineEntity l2 = new SettlementLineEntity(
                batch.getBatchId(), "TXN-002", new BigDecimal("40000.00000000"), "KRW", false);
        lineRepository.save(l1);
        lineRepository.save(l2);

        SettlementBatchEntity reloaded = batchRepository.findById("BATCH-2026-06-09-LOCAL").orElseThrow();
        assertThat(reloaded.getPartnerId()).isEqualTo("GME_REMIT");
        assertThat(reloaded.getBusinessDate()).isEqualTo(LocalDate.of(2026, 6, 9));
        assertThat(reloaded.getStatus()).isEqualTo("PENDING");
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo(new BigDecimal("100000.00000000"));
        assertThat(reloaded.getTotalCurrency()).isEqualTo("KRW");

        List<SettlementLineEntity> lines = lineRepository.findByBatchId(batch.getBatchId());
        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(SettlementLineEntity::getTxnRef)
                .containsExactlyInAnyOrder("TXN-001", "TXN-002");
        assertThat(lines).allSatisfy(l -> {
            assertThat(l.getCurrency()).isEqualTo("KRW");
            assertThat(l.isMatched()).isFalse();
        });
    }

    @Test
    void readBackPreservesMatchedFlag() {
        SettlementBatchEntity batch = new SettlementBatchEntity(
                "BATCH-MATCHED-CHECK",
                "SENDMN",
                LocalDate.of(2026, 6, 9),
                "GENERATED",
                new BigDecimal("50000.00000000"),
                "KRW",
                Instant.parse("2026-06-09T02:30:00Z"));
        batchRepository.save(batch);

        SettlementLineEntity matchedLine = new SettlementLineEntity(
                batch.getBatchId(), "TXN-100", new BigDecimal("30000.00000000"), "KRW", true);
        SettlementLineEntity unmatchedLine = new SettlementLineEntity(
                batch.getBatchId(), "TXN-101", new BigDecimal("20000.00000000"), "KRW", false);
        lineRepository.save(matchedLine);
        lineRepository.save(unmatchedLine);

        lineRepository.flush();

        List<SettlementLineEntity> matched = lineRepository.findByBatchIdAndMatched(batch.getBatchId(), true);
        List<SettlementLineEntity> unmatched = lineRepository.findByBatchIdAndMatched(batch.getBatchId(), false);

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getTxnRef()).isEqualTo("TXN-100");
        assertThat(matched.get(0).isMatched()).isTrue();

        assertThat(unmatched).hasSize(1);
        assertThat(unmatched.get(0).getTxnRef()).isEqualTo("TXN-101");
        assertThat(unmatched.get(0).isMatched()).isFalse();
    }

    @Test
    void reconExceptionRows_roundTripLineMatcherResults() {
        LineMatcher matcher = new LineMatcher();
        List<ReconLine> lines = matcher.match(
                Map.of("MERCH-A", new BigDecimal("10000"), "MERCH-B", new BigDecimal("25000")),
                Map.of("MERCH-A", new BigDecimal("10000"), "MERCH-B", new BigDecimal("24000")));

        Instant now = Instant.parse("2026-06-10T05:00:00Z");
        lines.forEach(line -> reconExceptionRepository.save(
                ReconExceptionEntity.fromReconLine("BATCH-RECON-H2", line, now)));
        reconExceptionRepository.flush();

        List<ReconExceptionEntity> reloaded = reconExceptionRepository.findByBatchId("BATCH-RECON-H2");
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded).extracting(ReconExceptionEntity::getMatchStatus)
                .containsExactlyInAnyOrder(MatchStatus.MATCHED, MatchStatus.DISCREPANCY);

        ReconLine discrepancy = reloaded.stream()
                .filter(e -> e.getMatchStatus() == MatchStatus.DISCREPANCY)
                .findFirst().orElseThrow()
                .toReconLine();
        assertThat(discrepancy.merchantId()).isEqualTo("MERCH-B");
        assertThat(discrepancy.gmeAmount()).isEqualByComparingTo("25000");
        assertThat(discrepancy.schemeAmount()).isEqualByComparingTo("24000");
        assertThat(discrepancy.discrepancyAmount()).isEqualByComparingTo("1000");
        assertThat(discrepancy.requiresAttention()).isTrue();

        assertThat(reconExceptionRepository
                .findByBatchIdAndMatchStatus("BATCH-RECON-H2", MatchStatus.DISCREPANCY)).hasSize(1);
        assertThat(reconExceptionRepository
                .countByBatchIdAndMatchStatusNot("BATCH-RECON-H2", MatchStatus.MATCHED)).isEqualTo(1);
    }

    /**
     * Full batch-tied recon pipeline on real repos (H2): persist a GENERATED batch with NET settlement
     * lines, run {@link ReconDiffEngine#runDiffForBatch} against a ZP0062 file, and assert that the
     * persisted side drives the diff — a discrepancy creates exactly one exception row, the matched
     * merchant's lines are flagged, the batch advances to RECEIVED (holding the open exception), and a
     * re-run is idempotent (no duplicate exception rows).
     */
    @Test
    void runDiffForBatch_persistsExceptionsAndAdvancesStatus() {
        // Engine over the real repositories; TransactionQueryPort is unused on the batch-tied path.
        com.gme.pay.settlement.port.TransactionQueryPort noTxnPort =
                new com.gme.pay.settlement.port.TransactionQueryPort() {
                    public List<com.gme.pay.settlement.model.TransactionRecord> findUnbatchedApproved(LocalDate d) { return List.of(); }
                    public List<com.gme.pay.settlement.model.TransactionRecord> findUnbatchedRefunded(LocalDate d) { return List.of(); }
                    public List<com.gme.pay.settlement.model.TransactionRecord> findByBatchId(Long id) { return List.of(); }
                };
        ReconDiffEngine engine = new ReconDiffEngine(
                noTxnPort, new LineMatcher(), reconExceptionRepository,
                batchRepository, lineRepository, (ref, residual, ccy) -> true,
                new com.gme.pay.settlement.alert.ReconBreakAlerter(event -> {}));

        SettlementBatchEntity batch = new SettlementBatchEntity(
                "ZP0061-20260615-MORNING", "GME_REMIT", LocalDate.of(2026, 6, 15),
                SettlementBatchStatus.GENERATED.name(),
                new BigDecimal("59720.00000000"), "KRW", Instant.parse("2026-06-15T02:00:00Z"));
        batch.setFileType("ZP0061");
        batch.setSettlementWindow("MORNING");
        batchRepository.save(batch);

        // MRC001 booked net 34720 (matches), MRC002 booked net 25000 (scheme will say 24000 → discrepancy).
        SettlementLineEntity l1 = new SettlementLineEntity(
                batch.getBatchId(), "TXN-A", new BigDecimal("34720"), "KRW", false);
        l1.setMerchantId("MRC001");
        l1.setSettlementType("N");
        SettlementLineEntity l2 = new SettlementLineEntity(
                batch.getBatchId(), "TXN-B", new BigDecimal("25000"), "KRW", false);
        l2.setMerchantId("MRC002");
        l2.setSettlementType("N");
        lineRepository.save(l1);
        lineRepository.save(l2);
        lineRepository.flush();

        List<ZeroPayResultRecord> recs = new ZP0062Parser().parse(List.of(
                "ZP006220260615001",
                "MRC001          0000000000034720",
                "MRC002          0000000000024000",
                "EOF0000000002000000000000058720"));

        engine.runDiffForBatch(batch, recs);

        // One DISCREPANCY exception for MRC002; MRC001 matched (not persisted as exception).
        List<ReconExceptionEntity> exceptions = reconExceptionRepository.findByBatchId(batch.getBatchId());
        assertThat(exceptions).hasSize(1);
        assertThat(exceptions.get(0).getMerchantId()).isEqualTo("MRC002");
        assertThat(exceptions.get(0).getMatchStatus()).isEqualTo(MatchStatus.DISCREPANCY);
        assertThat(exceptions.get(0).getDiscrepancyAmount()).isEqualByComparingTo("1000");

        // MRC001's line flagged matched; MRC002's not.
        assertThat(lineRepository.findByBatchIdAndMatched(batch.getBatchId(), true))
                .extracting(SettlementLineEntity::getMerchantId).containsExactly("MRC001");

        // Batch received (holds the open discrepancy), not reconciled.
        assertThat(batchRepository.findById(batch.getBatchId()).orElseThrow().getStatus())
                .isEqualTo(SettlementBatchStatus.RECEIVED.name());

        // Idempotent re-run: still exactly one exception row, no accumulation.
        engine.runDiffForBatch(batchRepository.findById(batch.getBatchId()).orElseThrow(), recs);
        assertThat(reconExceptionRepository.findByBatchId(batch.getBatchId())).hasSize(1);
    }
}
