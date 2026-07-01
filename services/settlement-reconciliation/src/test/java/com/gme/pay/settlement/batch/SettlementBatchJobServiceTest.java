package com.gme.pay.settlement.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.PartnerConfigPort;
import com.gme.pay.settlement.port.PartnerConfigPort.PartnerSettlementConfig;
import com.gme.pay.settlement.port.RefundedTransactionPort;
import com.gme.pay.settlement.port.RefundedTransactionPort.RefundLeg;
import com.gme.pay.settlement.port.TransactionQueryPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Orchestration of an outbound window run, with the upstream ports stubbed (real booking + factory +
 * builder, mocked repos + outbox). Proves the full pay-window → file → settlement.completed flow.
 */
class SettlementBatchJobServiceTest {

    private final TransactionQueryPort txnPort = mock(TransactionQueryPort.class);
    private final RefundedTransactionPort refundedPort = mock(RefundedTransactionPort.class);
    private final PartnerConfigPort partnerPort = mock(PartnerConfigPort.class);
    private final SettlementBookingService booking = new SettlementBookingService();
    private final SettlementBatchRepository batchRepo = mock(SettlementBatchRepository.class);
    private final SettlementLineRepository lineRepo = mock(SettlementLineRepository.class);
    private final EventPublisher outbox = mock(EventPublisher.class);
    private final SettlementBatchFactory factory = new SettlementBatchFactory(batchRepo);
    // Existing tests run with the window cutoff DISABLED ("","") so the now()-stamped fixtures are never
    // dropped; the cutoff and refund-clawback behaviours get their own job instances below.
    private final SettlementBatchJobService job = new SettlementBatchJobService(
            txnPort, partnerPort, booking, factory, batchRepo, lineRepo, outbox, "", "");

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static TransactionRecord net(String ref, String merchant, long payout, String feeRate) {
        return new TransactionRecord(1L, ref, "ZP-" + ref, merchant, BigDecimal.valueOf(payout),
                'N', new BigDecimal(feeRate), "APPROVED", OffsetDateTime.now(), null);
    }

    private static TransactionRecord gross(String ref, String merchant, long payout) {
        return new TransactionRecord(2L, ref, "ZP-" + ref, merchant, BigDecimal.valueOf(payout),
                'G', BigDecimal.ZERO, "APPROVED", OffsetDateTime.now(), null);
    }

    /** A NET approved txn stamped with a specific scheme-approval time (for window-cutoff tests). */
    private static TransactionRecord netApprovedAt(String ref, String merchant, long payout,
                                                   String feeRate, OffsetDateTime approvedAt) {
        return new TransactionRecord(1L, ref, "ZP-" + ref, merchant, BigDecimal.valueOf(payout),
                'N', new BigDecimal(feeRate), "APPROVED", approvedAt, null);
    }

    /** A REFUNDED txn (the original payment's payout being clawed back). */
    private static TransactionRecord refund(String ref, String merchant, long payout, char type) {
        return new TransactionRecord(3L, ref, "ZP-" + ref, merchant, BigDecimal.valueOf(payout),
                type, BigDecimal.ZERO, "REFUNDED", OffsetDateTime.now(), null);
    }

    private static OffsetDateTime kstToday(int hour, int minute) {
        return LocalDate.now(KST).atTime(hour, minute).atZone(KST).toOffsetDateTime();
    }

    @Test
    @DisplayName("generates a batch (booked net), persists a line per txn, emits one settlement.completed")
    void generatesBatchAndEmitsEvent() {
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partnerPort.resolve(any())).thenReturn(PartnerSettlementConfig.defaults("X")); // HALF_UP / KRW
        when(txnPort.findUnbatchedApproved(any())).thenReturn(List.of(
                net("T1", "M001", 35000, "0.008"),   // fee 280 → net 34720
                gross("T2", "M002", 50000)));          // net 50000

        SettlementBatchEntity batch = job.runWindow("ZP0061", "MORNING");

        assertEquals(SettlementBatchStatus.GENERATED.name(), batch.getStatus());
        assertEquals(0, batch.getNetSettlementAmount().compareTo(new BigDecimal("84720")),
                "net = 34720 (NET, fee 280) + 50000 (GROSS)");
        assertEquals(0, batch.getMerchantFeeTotal().compareTo(new BigDecimal("280")));
        assertEquals(2, batch.getRecordCount());
        assertEquals("KRW", batch.getSettleCurrency());
        assertNotNull(batch.getFileChecksum());
        verify(lineRepo, times(2)).save(any());           // one line per txn

        ArgumentCaptor<DomainEvent> evt = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outbox).publish(evt.capture());
        assertEquals("settlement.completed", evt.getValue().eventType());
        assertEquals(batch.getBatchId(), evt.getValue().aggregateId());
    }

    @Test
    @DisplayName("already-GENERATED batch → idempotent no-op (no lines, no event)")
    void idempotentNoOp() {
        SettlementBatchEntity existing = new SettlementBatchEntity();
        existing.setBatchId("ZP0061-20260618-MORNING");
        existing.setStatus(SettlementBatchStatus.GENERATED.name());
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(any(), any(), any()))
                .thenReturn(Optional.of(existing));

        SettlementBatchEntity b = job.runWindow("ZP0061", "MORNING");

        assertSame(existing, b);
        verify(outbox, never()).publish(any());
        verify(lineRepo, never()).save(any());
    }

    @Test
    @DisplayName("a merchant with BOTH NET and GROSS txns yields one row per type, never a blended row")
    void mixedTypeMerchantSplitByType() {
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partnerPort.resolve(any())).thenReturn(PartnerSettlementConfig.defaults("X"));
        when(txnPort.findUnbatchedApproved(any())).thenReturn(List.of(
                net("T1", "M009", 10000, "0.01"),   // NET: fee 100 → net 9900
                gross("T2", "M009", 20000)));         // GROSS: net 20000 (fee dropped if blended)

        SettlementBatchEntity batch = job.runWindow("ZP0061", "MORNING");

        assertEquals(2, batch.getRecordCount(), "one row per (merchant, type) — not blended into one");
        assertEquals(0, batch.getNetSettlementAmount().compareTo(new BigDecimal("29900")),
                "9900 (NET, fee 100) + 20000 (GROSS)");
        verify(lineRepo, times(2)).save(any());
    }

    @Test
    @DisplayName("window cutoff: a txn approved after the morning cutoff is left for the afternoon batch")
    void windowCutoffExcludesLateApprovals() {
        SettlementBatchJobService cutoffJob = new SettlementBatchJobService(
                txnPort, partnerPort, booking, factory, batchRepo, lineRepo, outbox, "04:30", "13:30");
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partnerPort.resolve(any())).thenReturn(PartnerSettlementConfig.defaults("X"));
        when(txnPort.findUnbatchedApproved(any())).thenReturn(List.of(
                netApprovedAt("EARLY", "M001", 10000, "0", kstToday(3, 0)),    // 03:00 ≤ 04:30 → included
                netApprovedAt("LATE", "M002", 50000, "0", kstToday(10, 0))));   // 10:00 > 04:30 → excluded

        SettlementBatchEntity batch = job0463(cutoffJob);

        assertEquals(1, batch.getRecordCount(), "only the pre-cutoff txn is in the morning file");
        assertEquals(0, batch.getNetSettlementAmount().compareTo(new BigDecimal("10000")),
                "net = the single pre-cutoff txn (fee rate 0)");
        verify(lineRepo, times(1)).save(any());
    }

    // tiny helper so the cutoff test reads cleanly (MORNING window exercises the 04:30 cutoff).
    private static SettlementBatchEntity job0463(SettlementBatchJobService j) {
        return j.runWindow("ZP0061", "MORNING");
    }

    @Test
    @DisplayName("refund clawback: a prior-settled refund nets out (net = gross − fee − refund) and is reported")
    void refundClawbackNetsPriorSettledRefund() {
        SettlementBatchJobService refundJob = new SettlementBatchJobService(
                txnPort, partnerPort, booking, factory, batchRepo, lineRepo, outbox, "", "");
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partnerPort.resolve(any())).thenReturn(PartnerSettlementConfig.defaults("X"));
        when(txnPort.findUnbatchedApproved(any())).thenReturn(List.of(
                net("T1", "M001", 35000, "0.008")));   // fee 280
        when(txnPort.findUnbatchedRefunded(any())).thenReturn(List.of(
                refund("R1", "M001", 5000, 'N')));
        // R1's original payment was settled in a prior batch and not yet clawed back → eligible.
        when(lineRepo.existsByTxnRefAndAmountGreaterThan(eq("R1"), any())).thenReturn(true);
        when(lineRepo.existsByTxnRefAndAmountLessThan(eq("R1"), any())).thenReturn(false);

        SettlementBatchEntity batch = refundJob.runWindow("ZP0061", "MORNING");

        // net = 35000 − 280 (fee) − 5000 (refund) = 29720; gross = net + fee + refund stays balanced.
        assertEquals(0, batch.getNetSettlementAmount().compareTo(new BigDecimal("29720")),
                "net = gross 35000 − fee 280 − refund 5000");
        assertEquals(0, batch.getMerchantFeeTotal().compareTo(new BigDecimal("280")),
                "merchant_fee_total = fee only (refund is its own file field)");
        verify(lineRepo, times(2)).save(any());   // 1 payment line + 1 negative refund clawback line
    }

    @Test
    @DisplayName("refund clawback: a same-day approve→refund (never settled) is NOT clawed back")
    void refundNotClawedWhenNeverSettled() {
        SettlementBatchJobService refundJob = new SettlementBatchJobService(
                txnPort, partnerPort, booking, factory, batchRepo, lineRepo, outbox, "", "");
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partnerPort.resolve(any())).thenReturn(PartnerSettlementConfig.defaults("X"));
        when(txnPort.findUnbatchedApproved(any())).thenReturn(List.of(
                net("T1", "M001", 35000, "0.008")));   // fee 280 → net 34720
        when(txnPort.findUnbatchedRefunded(any())).thenReturn(List.of(
                refund("R2", "M001", 5000, 'N')));
        // R2 has no prior settled payment line → never paid out → must NOT be clawed back.
        when(lineRepo.existsByTxnRefAndAmountGreaterThan(eq("R2"), any())).thenReturn(false);

        SettlementBatchEntity batch = refundJob.runWindow("ZP0061", "MORNING");

        assertEquals(0, batch.getNetSettlementAmount().compareTo(new BigDecimal("34720")),
                "net = gross 35000 − fee 280; the never-settled refund nets to zero (no clawback)");
        verify(lineRepo, times(1)).save(any());   // only the payment line; no clawback line
    }

    @Test
    @DisplayName("cross-date refund: a prior-day payment refunded today reduces the netted settlement")
    void crossDateRefundReducesNet() {
        SettlementBatchJobService crossJob = new SettlementBatchJobService(
                txnPort, partnerPort, booking, factory, batchRepo, lineRepo, outbox, refundedPort, "", "");
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partnerPort.resolve(any())).thenReturn(PartnerSettlementConfig.defaults("X"));
        // Today's fresh payment for M001: net 50000 (fee rate 0).
        when(txnPort.findUnbatchedApproved(any())).thenReturn(List.of(
                net("T1", "M001", 50000, "0")));
        // No same-day (creation-date) refunds.
        when(txnPort.findUnbatchedRefunded(any())).thenReturn(List.of());
        // A refund processed TODAY of a payment (PAY-OLD) settled on a PRIOR day → cross-date claw-back.
        when(refundedPort.findRefundedOn(any())).thenReturn(List.of(
                new RefundLeg("RFND-X", "PAY-OLD", "M001", new BigDecimal("8000"),
                        LocalDate.now(KST), OffsetDateTime.now())));
        // PAY-OLD was settled in a prior batch (positive line) and RFND-X not yet clawed back.
        when(lineRepo.existsByTxnRefAndAmountGreaterThan(eq("PAY-OLD"), any())).thenReturn(true);
        when(lineRepo.existsByTxnRefAndAmountLessThan(eq("RFND-X"), any())).thenReturn(false);

        SettlementBatchEntity batch = crossJob.runWindow("ZP0061", "MORNING");

        // net = 50000 (today's payment) − 8000 (cross-date claw-back) = 42000.
        assertEquals(0, batch.getNetSettlementAmount().compareTo(new BigDecimal("42000")),
                "net = today's 50000 minus the 8000 cross-date refund clawed back from a prior settlement");
        verify(lineRepo, times(2)).save(any());   // 1 payment line + 1 negative cross-date claw-back line
    }

    @Test
    @DisplayName("cross-date refund: original payment never settled → not clawed back (no net change)")
    void crossDateRefundNotClawedWhenOriginalUnsettled() {
        SettlementBatchJobService crossJob = new SettlementBatchJobService(
                txnPort, partnerPort, booking, factory, batchRepo, lineRepo, outbox, refundedPort, "", "");
        when(batchRepo.findByFileTypeAndBusinessDateAndSettlementWindow(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partnerPort.resolve(any())).thenReturn(PartnerSettlementConfig.defaults("X"));
        when(txnPort.findUnbatchedApproved(any())).thenReturn(List.of(net("T1", "M001", 50000, "0")));
        when(txnPort.findUnbatchedRefunded(any())).thenReturn(List.of());
        when(refundedPort.findRefundedOn(any())).thenReturn(List.of(
                new RefundLeg("RFND-Y", "PAY-NEVER", "M001", new BigDecimal("8000"),
                        LocalDate.now(KST), OffsetDateTime.now())));
        // PAY-NEVER has no prior settled line → cross-date refund nets to zero.
        when(lineRepo.existsByTxnRefAndAmountGreaterThan(eq("PAY-NEVER"), any())).thenReturn(false);

        SettlementBatchEntity batch = crossJob.runWindow("ZP0061", "MORNING");

        assertEquals(0, batch.getNetSettlementAmount().compareTo(new BigDecimal("50000")),
                "net unchanged — the refund's original payment was never settled, so nothing to claw back");
        verify(lineRepo, times(1)).save(any());   // only the payment line; no claw-back line
    }
}
