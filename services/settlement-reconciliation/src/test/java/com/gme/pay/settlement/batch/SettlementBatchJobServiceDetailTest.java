package com.gme.pay.settlement.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.settlement.booking.SettlementBookingService;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.PartnerConfigPort;
import com.gme.pay.settlement.port.TransactionQueryPort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Orchestration of the ~22:00 DETAIL window (ZP0065 payment detail / ZP0066 refund detail). Rows are built
 * ENTIRELY from the day's ZP0061/ZP0063 request-batch settlement_lines (positive=payments, negative=
 * claw-backs) using the V008 snapshot columns — so the detail files tie out to the summary by construction
 * and never re-fetch the live txn. The job READS those lines but writes none of its own (so it cannot
 * poison the aggregate clawback markers), persists the batch + emits the event.
 */
class SettlementBatchJobServiceDetailTest {

    private final TransactionQueryPort txnPort = mock(TransactionQueryPort.class);
    private final PartnerConfigPort partnerPort = mock(PartnerConfigPort.class);
    private final SettlementBookingService booking = new SettlementBookingService();
    private final SettlementBatchRepository batchRepo = mock(SettlementBatchRepository.class);
    private final SettlementLineRepository lineRepo = mock(SettlementLineRepository.class);
    private final EventPublisher outbox = mock(EventPublisher.class);
    private final SettlementBatchFactory factory = new SettlementBatchFactory(batchRepo);
    private final SettlementBatchJobService job = new SettlementBatchJobService(
            txnPort, partnerPort, booking, factory, batchRepo, lineRepo, outbox, "", "");

    private static SettlementBatchEntity reqBatch(String batchId) {
        SettlementBatchEntity b = new SettlementBatchEntity();
        b.setBatchId(batchId);
        return b;
    }

    /** A request-batch line with the V008 detail snapshot: positive amount = payment, negative = claw-back. */
    private static SettlementLineEntity line(String batchId, String txnRef, long amount) {
        SettlementLineEntity l = new SettlementLineEntity(batchId, txnRef, BigDecimal.valueOf(amount), "KRW", false);
        l.setSettlementType("N");
        l.setMerchantId("M-" + txnRef);
        l.setSchemeRef("ZP-" + txnRef);
        return l;
    }

    @Test
    @DisplayName("ZP0065: payment rows built from request-batch positive lines; writes NO settlement_lines")
    void runDetail0065() {
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(eq("ZP0065"), any(), eq("DETAIL")))
                .thenReturn(Optional.empty());                                       // fresh detail batch
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(eq("ZP0061"), any(), eq("MORNING")))
                .thenReturn(Optional.of(reqBatch("REQ-1")));
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(eq("ZP0063"), any(), eq("AFTERNOON")))
                .thenReturn(Optional.empty());
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepo.findByBatchId("REQ-1")).thenReturn(List.of(
                line("REQ-1", "T1", 10000),
                line("REQ-1", "T2", 20000),
                line("REQ-1", "T3", 30000)));

        SettlementBatchEntity batch = job.runDetailWindow("ZP0065");

        assertEquals(SettlementBatchStatus.GENERATED.name(), batch.getStatus());
        assertEquals(3, batch.getRecordCount(), "one row per settled payment line");
        assertEquals(0, batch.getTotalAmount().compareTo(new BigDecimal("60000")),
                "ZP0065 total ties out to the request-batch gross");
        assertNull(batch.getNetSettlementAmount(),
                "detail total is gross, not net — net_settlement_amount must stay unset");
        verify(lineRepo, never()).save(any());   // detail must not touch settlement_lines (clawback markers)

        ArgumentCaptor<DomainEvent> evt = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outbox, times(1)).publish(evt.capture());
        assertEquals("settlement.completed", evt.getValue().eventType());
        assertEquals(batch.getBatchId(), evt.getValue().aggregateId());
    }

    @Test
    @DisplayName("ZP0066: refund rows from claw-back (negative) lines only; payment lines excluded; absolute amounts")
    void runDetail0066() {
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(eq("ZP0066"), any(), eq("DETAIL")))
                .thenReturn(Optional.empty());
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(eq("ZP0061"), any(), eq("MORNING")))
                .thenReturn(Optional.of(reqBatch("REQ-1")));
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(eq("ZP0063"), any(), eq("AFTERNOON")))
                .thenReturn(Optional.empty());
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // REQ-1 holds a payment (positive) AND two claw-backs (negative): only the claw-backs feed ZP0066.
        when(lineRepo.findByBatchId("REQ-1")).thenReturn(List.of(
                line("REQ-1", "P9", 99000),       // a payment — must NOT appear in the refund file
                line("REQ-1", "R1", -5000),
                line("REQ-1", "R2", -3000)));

        SettlementBatchEntity batch = job.runDetailWindow("ZP0066");

        assertEquals(2, batch.getRecordCount(), "only the two claw-back lines");
        assertEquals(0, batch.getTotalAmount().compareTo(new BigDecimal("8000")),
                "total = SUM(absolute refund amounts)");
        verify(lineRepo, never()).save(any());
        verify(outbox, times(1)).publish(any());
    }

    @Test
    @DisplayName("already-GENERATED detail batch → idempotent no-op (no event)")
    void idempotentNoOp() {
        SettlementBatchEntity existing = new SettlementBatchEntity();
        existing.setBatchId("ZP0065-20260626-DETAIL");
        existing.setStatus(SettlementBatchStatus.GENERATED.name());
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(eq("ZP0065"), any(), eq("DETAIL")))
                .thenReturn(Optional.of(existing));

        job.runDetailWindow("ZP0065");

        verify(outbox, never()).publish(any());
        verify(lineRepo, never()).save(any());
    }

    @Test
    @DisplayName("file-type guards: runDetailWindow rejects request codes; runWindow rejects detail codes")
    void fileTypeGuards() {
        assertThrows(IllegalArgumentException.class, () -> job.runDetailWindow("ZP0061"));
        assertThrows(IllegalArgumentException.class, () -> job.runWindow("ZP0065", "DETAIL"));
    }
}
