package com.gme.pay.settlement.rerun;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.settlement.alert.ReconAlertEvent;
import com.gme.pay.settlement.alert.ReconBreakAlerter;
import com.gme.pay.settlement.batch.SettlementBatchStatus;
import com.gme.pay.settlement.parser.ZP0062Parser;
import com.gme.pay.settlement.parser.ZP0064Parser;
import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.recon.BatchNotFoundException;
import com.gme.pay.settlement.recon.LineMatcher;
import com.gme.pay.settlement.recon.ReconDiffEngine;
import com.gme.pay.settlement.scheduler.ReconFileSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ReconRerunService} — the operator recon re-run. Wires a real {@link ReconDiffEngine}
 * over mock repositories, a real {@link ReconFileSource} pointing at a temp inbox, and a capturing alert
 * publisher, so we assert end-to-end: idempotent re-run (no duplicate lines), break → RECON_BREAK alert +
 * persisted exception, clean batch → no alert.
 */
@ExtendWith(MockitoExtension.class)
class ReconRerunServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 15);
    private static final String BATCH_ID = "ZP0061-20260615-MORNING";

    @Mock private SettlementBatchRepository batchRepository;
    @Mock private ReconExceptionRepository reconExceptionRepository;
    @Mock private SettlementLineRepository lineRepository;
    @Mock private com.gme.pay.settlement.port.TransactionQueryPort transactionQueryPort;

    private CapturingPublisher alertPublisher;
    private ReconRerunService service;
    private final List<ReconExceptionEntity> savedExceptions = new ArrayList<>();

    private static final class CapturingPublisher implements EventPublisher {
        final List<DomainEvent> events = new ArrayList<>();
        @Override public void publish(DomainEvent e) { events.add(e); }
    }

    @BeforeEach
    void setUp(@org.junit.jupiter.api.io.TempDir Path inbox) throws IOException {
        alertPublisher = new CapturingPublisher();
        ReconBreakAlerter alerter = new ReconBreakAlerter(alertPublisher);
        ReconDiffEngine engine = new ReconDiffEngine(
                transactionQueryPort, new LineMatcher(), reconExceptionRepository,
                batchRepository, lineRepository, (ref, residual, ccy) -> true, alerter);

        ReconFileSource fileSource = new ReconFileSource(inbox.toString());
        service = new ReconRerunService(
                batchRepository, fileSource, new ZP0062Parser(), new ZP0064Parser(), engine);

        // record every persisted exception; delete clears the running list (idempotent re-insert)
        lenient().when(reconExceptionRepository.save(any())).thenAnswer(i -> {
            ReconExceptionEntity e = i.getArgument(0);
            savedExceptions.add(e);
            return e;
        });
        lenient().doAnswer(i -> { savedExceptions.clear(); return null; })
                .when(reconExceptionRepository).deleteByBatchId(BATCH_ID);
        lenient().when(lineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(batchRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        writeScheme(inbox, "34000");   // default: scheme says 34000 (mismatch vs booked 34720)
    }

    /** Write a ZP0062 scheme file for MRC001 crediting the given amount. */
    private void writeScheme(Path inbox, String amount) throws IOException {
        String padded = String.format("%016d", Long.parseLong(amount));
        Files.write(inbox.resolve("ZP0062_20260615.txt"), List.of(
                "ZP006220260615001",
                "MRC001          " + padded,
                "EOF0000000001000000000000" + amount));
    }

    private SettlementBatchEntity batch(String status) {
        SettlementBatchEntity b = new SettlementBatchEntity(
                BATCH_ID, "MRC001", DATE, status, new BigDecimal("34720"), "KRW", Instant.now());
        b.setFileType("ZP0061");
        b.setSettlementWindow("MORNING");
        b.setSettleCurrency("KRW");
        return b;
    }

    private SettlementLineEntity line(String amount) {
        SettlementLineEntity l = new SettlementLineEntity(BATCH_ID, "TXN-MRC001", new BigDecimal(amount), "KRW", false);
        l.setMerchantId("MRC001");
        l.setSettlementType("N");
        return l;
    }

    @Test
    @DisplayName("forced mismatch → RECON_BREAK alert emitted + exception created, one per run")
    void mismatch_emitsAlertAndException() {
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch(SettlementBatchStatus.GENERATED.name())));
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(line("34720")));   // booked 34720 vs scheme 34000

        ReconRerunResponse resp = service.rerun(
                new ReconRerunRequest(BATCH_ID, null, "ops@gmeremit.com", "customer disputed"));

        assertThat(resp.totalExceptions()).isEqualTo(1);
        assertThat(resp.batchesRerun()).isEqualTo(1);
        assertThat(savedExceptions).hasSize(1);
        assertThat(alertPublisher.events).hasSize(1);
        assertThat(((ReconAlertEvent) alertPublisher.events.get(0)).payload().alertType()).isEqualTo("RECON_BREAK");
        assertThat(((ReconAlertEvent) alertPublisher.events.get(0)).payload().subjectRef()).isEqualTo(BATCH_ID);
    }

    @Test
    @DisplayName("re-run is idempotent — two runs yield the same result and no duplicate exception lines")
    void idempotentReRun() {
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch(SettlementBatchStatus.GENERATED.name())));
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(line("34720")));

        ReconRerunRequest req = new ReconRerunRequest(BATCH_ID, null, "ops@gmeremit.com", "re-check");
        ReconRerunResponse first = service.rerun(req);
        ReconRerunResponse second = service.rerun(req);

        assertThat(second.totalExceptions()).isEqualTo(first.totalExceptions()).isEqualTo(1);
        assertThat(second.totalMatched()).isEqualTo(first.totalMatched());
        // delete-before-insert each run → still exactly one exception row, not two
        assertThat(savedExceptions).hasSize(1);
    }

    @Test
    @DisplayName("clean batch (scheme ties out) → no alert, no exception, batch RECONCILED")
    void cleanBatch_noAlert(@org.junit.jupiter.api.io.TempDir Path inbox) throws IOException {
        // rewrite scheme to tie out exactly to the booked 34720
        Files.write(inbox.resolve("ZP0062_20260615.txt"), List.of(
                "ZP006220260615001",
                "MRC001          0000000000034720",
                "EOF0000000001000000000000034720"));
        service = new ReconRerunService(
                batchRepository, new ReconFileSource(inbox.toString()),
                new ZP0062Parser(), new ZP0064Parser(),
                new ReconDiffEngine(transactionQueryPort, new LineMatcher(), reconExceptionRepository,
                        batchRepository, lineRepository, (ref, residual, ccy) -> true,
                        new ReconBreakAlerter(alertPublisher)));

        SettlementBatchEntity b = batch(SettlementBatchStatus.GENERATED.name());
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(b));
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(line("34720")));

        ReconRerunResponse resp = service.rerun(
                new ReconRerunRequest(BATCH_ID, null, "ops@gmeremit.com", "sanity check"));

        assertThat(resp.totalExceptions()).isZero();
        assertThat(resp.totalMatched()).isEqualTo(1);
        assertThat(alertPublisher.events).isEmpty();
        assertThat(savedExceptions).isEmpty();
        assertThat(b.getStatus()).isEqualTo(SettlementBatchStatus.RECONCILED.name());
    }

    @Test
    @DisplayName("neither batchId nor settlementDate → IllegalArgumentException")
    void noScope_throws() {
        assertThatThrownBy(() -> service.rerun(new ReconRerunRequest(null, null, "ops@gmeremit.com", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("both batchId and settlementDate → IllegalArgumentException")
    void bothScopes_throws() {
        assertThatThrownBy(() -> service.rerun(new ReconRerunRequest(BATCH_ID, DATE, "ops@gmeremit.com", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("unknown batchId → BatchNotFoundException")
    void unknownBatch_throws() {
        when(batchRepository.findById("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rerun(new ReconRerunRequest("NOPE", null, "ops@gmeremit.com", "x")))
                .isInstanceOf(BatchNotFoundException.class);
    }

    @Test
    @DisplayName("settlementDate scope re-runs every request batch for the day")
    void byDate_rerunsAllRequestBatches() {
        SettlementBatchEntity morning = batch(SettlementBatchStatus.GENERATED.name());
        when(batchRepository.findByBusinessDate(DATE)).thenReturn(List.of(morning));
        when(lineRepository.findByBatchId(BATCH_ID)).thenReturn(List.of(line("34720")));

        ReconRerunResponse resp = service.rerun(
                new ReconRerunRequest(null, DATE, "ops@gmeremit.com", "day re-run"));

        assertThat(resp.batchesRerun()).isEqualTo(1);
        assertThat(resp.batches().get(0).batchId()).isEqualTo(BATCH_ID);
    }
}
