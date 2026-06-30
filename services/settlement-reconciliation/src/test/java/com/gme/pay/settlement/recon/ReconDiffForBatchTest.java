package com.gme.pay.settlement.recon;

import com.gme.pay.settlement.batch.SettlementBatchStatus;
import com.gme.pay.settlement.parser.ZP0062Parser;
import com.gme.pay.settlement.parser.ZeroPayResultRecord;
import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ReconDiffEngine#runDiffForBatch} — the authoritative batch-tied recon path.
 *
 * <p>Verifies the GME side is the NET booked amount re-summed from the persisted settlement lines
 * (not live gross), idempotent re-processing, and batch-lifecycle advancement.
 */
@ExtendWith(MockitoExtension.class)
class ReconDiffForBatchTest {

    @Mock private TransactionQueryPort transactionQueryPort;
    @Mock private ReconExceptionRepository reconExceptionRepository;
    @Mock private SettlementBatchRepository batchRepository;
    @Mock private SettlementLineRepository lineRepository;

    private ReconDiffEngine engine;
    private final ZP0062Parser parser = new ZP0062Parser();

    private static final String BATCH_ID = "ZP0061-20260615-MORNING";

    @BeforeEach
    void setUp() {
        engine = new ReconDiffEngine(transactionQueryPort, new LineMatcher(),
                reconExceptionRepository, batchRepository, lineRepository);
        lenient().when(reconExceptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(lineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(batchRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private SettlementBatchEntity batch(String status) {
        SettlementBatchEntity b = new SettlementBatchEntity(
                BATCH_ID, "MRC001", LocalDate.of(2026, 6, 15), status,
                new BigDecimal("34720"), "KRW", Instant.now());
        b.setFileType("ZP0061");
        b.setSettlementWindow("MORNING");
        return b;
    }

    /** A persisted NET payment line: amount carries the booked (net) figure for the merchant. */
    private SettlementLineEntity line(String merchantId, String amount) {
        SettlementLineEntity l = new SettlementLineEntity(BATCH_ID, "TXN-" + merchantId,
                new BigDecimal(amount), "KRW", false);
        l.setMerchantId(merchantId);
        l.setSettlementType("N");
        return l;
    }

    @Test
    @DisplayName("net booked line ties to scheme net → MATCHED, batch → RECONCILED, no exception")
    void netMatch_reconciled() {
        // Line booked NET 34720 (gross 35000 − fee 280). Scheme confirms 34720 → exact tie-out.
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(line("MRC001", "34720")));
        List<ZeroPayResultRecord> recs = parser.parse(List.of(
                "ZP006220260615001",
                "MRC001          0000000000034720",
                "EOF0000000001000000000000034720"));

        SettlementBatchEntity b = batch(SettlementBatchStatus.GENERATED.name());
        List<ReconLine> result = engine.runDiffForBatch(b, recs);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).matchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(b.getStatus()).isEqualTo(SettlementBatchStatus.RECONCILED.name());
        verify(reconExceptionRepository).deleteByBatchId(BATCH_ID);   // idempotent clear
        verify(reconExceptionRepository, never()).save(any());        // nothing to flag
    }

    @Test
    @DisplayName("scheme amount differs from net → DISCREPANCY persisted, batch holds at RECEIVED")
    void discrepancy_received() {
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(line("MRC001", "34720")));
        List<ZeroPayResultRecord> recs = parser.parse(List.of(
                "ZP006220260615001",
                "MRC001          0000000000034000",   // scheme says 34000, expected 34720
                "EOF0000000001000000000000034000"));

        SettlementBatchEntity b = batch(SettlementBatchStatus.GENERATED.name());
        List<ReconLine> result = engine.runDiffForBatch(b, recs);

        assertThat(result.get(0).matchStatus()).isEqualTo(MatchStatus.DISCREPANCY);
        assertThat(result.get(0).discrepancyAmount()).isEqualByComparingTo("720");
        verify(reconExceptionRepository, times(1)).save(any(ReconExceptionEntity.class));
        assertThat(b.getStatus()).isEqualTo(SettlementBatchStatus.RECEIVED.name());   // not RECONCILED
    }

    @Test
    @DisplayName("re-processing the same file is idempotent — prior exceptions cleared, not duplicated")
    void idempotentReRun() {
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(line("MRC001", "34720")));
        List<ZeroPayResultRecord> recs = parser.parse(List.of(
                "ZP006220260615001",
                "MRC001          0000000000034000",
                "EOF0000000001000000000000034000"));

        engine.runDiffForBatch(batch(SettlementBatchStatus.GENERATED.name()), recs);
        engine.runDiffForBatch(batch(SettlementBatchStatus.GENERATED.name()), recs);

        // delete-before-insert each run → exactly one saved row per run, no accumulation
        verify(reconExceptionRepository, times(2)).deleteByBatchId(BATCH_ID);
        verify(reconExceptionRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("claw-back: payment line + negative refund line net to scheme credit → MATCHED")
    void clawbackNetsToSchemeCredit() {
        // Merchant paid 34720 then a 10000 refund clawed back → net 24720. Scheme credits 24720.
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(
                line("MRC001", "34720"), line("MRC001", "-10000")));
        List<ZeroPayResultRecord> recs = parser.parse(List.of(
                "ZP006220260615001",
                "MRC001          0000000000024720",
                "EOF0000000001000000000000024720"));

        List<ReconLine> result = engine.runDiffForBatch(batch(SettlementBatchStatus.GENERATED.name()), recs);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).matchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(result.get(0).gmeAmount()).isEqualByComparingTo("24720");
    }

    @Test
    @DisplayName("null batch → BatchNotFoundException")
    void nullBatch_throws() {
        assertThatThrownBy(() -> engine.runDiffForBatch(null, List.of()))
                .isInstanceOf(BatchNotFoundException.class);
    }

    @Test
    @DisplayName("already RECONCILED batch is a no-op (recon already closed)")
    void alreadyReconciled_noop() {
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(line("MRC001", "34720")));
        List<ZeroPayResultRecord> recs = parser.parse(List.of(
                "ZP006220260615001",
                "MRC001          0000000000034720",
                "EOF0000000001000000000000034720"));

        SettlementBatchEntity b = batch(SettlementBatchStatus.RECONCILED.name());
        engine.runDiffForBatch(b, recs);

        assertThat(b.getStatus()).isEqualTo(SettlementBatchStatus.RECONCILED.name());
        verify(batchRepository, never()).save(any());   // status untouched
    }
}
