package com.gme.pay.settlement.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
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
import com.gme.pay.settlement.port.TransactionQueryPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
    private final PartnerConfigPort partnerPort = mock(PartnerConfigPort.class);
    private final SettlementBookingService booking = new SettlementBookingService();
    private final SettlementBatchRepository batchRepo = mock(SettlementBatchRepository.class);
    private final SettlementLineRepository lineRepo = mock(SettlementLineRepository.class);
    private final EventPublisher outbox = mock(EventPublisher.class);
    private final SettlementBatchFactory factory = new SettlementBatchFactory(batchRepo);
    private final SettlementBatchJobService job = new SettlementBatchJobService(
            txnPort, partnerPort, booking, factory, batchRepo, lineRepo, outbox);

    private static TransactionRecord net(String ref, String merchant, long payout, String feeRate) {
        return new TransactionRecord(1L, ref, "ZP-" + ref, merchant, BigDecimal.valueOf(payout),
                'N', new BigDecimal(feeRate), "APPROVED", OffsetDateTime.now(), null);
    }

    private static TransactionRecord gross(String ref, String merchant, long payout) {
        return new TransactionRecord(2L, ref, "ZP-" + ref, merchant, BigDecimal.valueOf(payout),
                'G', BigDecimal.ZERO, "APPROVED", OffsetDateTime.now(), null);
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
}
