package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.scheme.zeropay.adapter.ZeroPayAdapterProperties;
import com.gme.pay.scheme.zeropay.adapter.ZeroPaySchemeAdapter;
import com.gme.pay.scheme.zeropay.adapter.model.BatchFile;
import com.gme.pay.scheme.zeropay.adapter.model.BatchType;
import com.gme.pay.scheme.zeropay.adapter.model.TransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ZeroPayBatchScheduler}.
 *
 * <p>Tests invoke the scheduler methods directly (no @Scheduled clock overhead). The
 * adapter is fully mocked so no file I/O occurs.</p>
 *
 * <p>Key assertions:
 * <ul>
 *   <li>When {@code batchEnabled=false} (the default), no adapter method is called.</li>
 *   <li>When {@code batchEnabled=true}, the scheduler calls the correct adapter method
 *       with the correct {@link BatchType}.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ZeroPayBatchSchedulerTest {

    @Mock private ZeroPaySchemeAdapter     adapter;
    @Mock private ZeroPayAdapterProperties properties;

    private ZeroPayBatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ZeroPayBatchScheduler(adapter, properties);
    }

    // -----------------------------------------------------------------------
    // Gating: when batchEnabled=false, no adapter calls are made
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generatePaymentResult: does NOT call adapter when batch disabled")
    void generatePaymentResult_batchDisabled_noAdapterCall() {
        when(properties.isBatchEnabled()).thenReturn(false);

        scheduler.generatePaymentResult();

        verify(adapter, never()).generatePaymentResultFile(any(), any());
        verify(adapter, never()).transferOutbound(any(), any());
    }

    @Test
    @DisplayName("generateRefundResult: does NOT call adapter when batch disabled")
    void generateRefundResult_batchDisabled_noAdapterCall() {
        when(properties.isBatchEnabled()).thenReturn(false);

        scheduler.generateRefundResult();

        verify(adapter, never()).generateRefundResultFile(any(), any());
    }

    @Test
    @DisplayName("generateMorningSettlementRequest: does NOT call adapter when batch disabled")
    void generateMorningSettlement_batchDisabled_noAdapterCall() {
        when(properties.isBatchEnabled()).thenReturn(false);

        scheduler.generateMorningSettlementRequest();

        verify(adapter, never()).generateSettlementRequestFile(any(), any());
    }

    @Test
    @DisplayName("generateAfternoonSettlementRequest: does NOT call adapter when batch disabled")
    void generateAfternoonSettlement_batchDisabled_noAdapterCall() {
        when(properties.isBatchEnabled()).thenReturn(false);

        scheduler.generateAfternoonSettlementRequest();

        verify(adapter, never()).generateSettlementRequestFile(any(), any());
    }

    @Test
    @DisplayName("generatePaymentDetailFile: does NOT call adapter when batch disabled")
    void generatePaymentDetail_batchDisabled_noAdapterCall() {
        when(properties.isBatchEnabled()).thenReturn(false);

        scheduler.generatePaymentDetailFile();

        verify(adapter, never()).generatePaymentResultFile(any(), any());
    }

    @Test
    @DisplayName("generateRefundDetailFile: does NOT call adapter when batch disabled")
    void generateRefundDetail_batchDisabled_noAdapterCall() {
        when(properties.isBatchEnabled()).thenReturn(false);

        scheduler.generateRefundDetailFile();

        verify(adapter, never()).generateRefundResultFile(any(), any());
    }

    // -----------------------------------------------------------------------
    // ~02:00 KST window: ZP0011 + ZP0021
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generatePaymentResult: calls generatePaymentResultFile(ZP0011) when enabled")
    void generatePaymentResult_enabled_callsZp0011() {
        when(properties.isBatchEnabled()).thenReturn(true);
        BatchFile mockFile = new BatchFile(BatchType.ZP0011, LocalDate.now(), 1,
                "data".getBytes(), 0, BigDecimal.ZERO);
        TransferResult mockTransfer = new TransferResult(true, "/out/ZP0011.dat", 4, "sha", 1);

        ArgumentCaptor<BatchType> typeCaptor = ArgumentCaptor.forClass(BatchType.class);
        when(adapter.generatePaymentResultFile(typeCaptor.capture(), any()))
                .thenReturn(mockFile);
        when(adapter.transferOutbound(any(), isNull())).thenReturn(mockTransfer);

        scheduler.generatePaymentResult();

        assertEquals(BatchType.ZP0011, typeCaptor.getValue());
        verify(adapter).transferOutbound(mockFile, null);
    }

    @Test
    @DisplayName("generateRefundResult: calls generateRefundResultFile(ZP0021) when enabled")
    void generateRefundResult_enabled_callsZp0021() {
        when(properties.isBatchEnabled()).thenReturn(true);
        BatchFile mockFile = new BatchFile(BatchType.ZP0021, LocalDate.now(), 1,
                "data".getBytes(), 0, BigDecimal.ZERO);
        TransferResult mockTransfer = new TransferResult(true, "/out/ZP0021.dat", 4, "sha", 1);

        ArgumentCaptor<BatchType> typeCaptor = ArgumentCaptor.forClass(BatchType.class);
        when(adapter.generateRefundResultFile(typeCaptor.capture(), any()))
                .thenReturn(mockFile);
        when(adapter.transferOutbound(any(), isNull())).thenReturn(mockTransfer);

        scheduler.generateRefundResult();

        assertEquals(BatchType.ZP0021, typeCaptor.getValue());
        verify(adapter).transferOutbound(mockFile, null);
    }

    // -----------------------------------------------------------------------
    // ~05:00 KST window: ZP0061
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generateMorningSettlementRequest: calls generateSettlementRequestFile(ZP0061)")
    void generateMorningSettlement_enabled_callsZp0061() {
        when(properties.isBatchEnabled()).thenReturn(true);
        BatchFile mockFile = new BatchFile(BatchType.ZP0061, LocalDate.now(), 1,
                "data".getBytes(), 0, BigDecimal.ZERO);
        TransferResult mockTransfer = new TransferResult(true, "/out/ZP0061.dat", 4, "sha", 1);

        ArgumentCaptor<BatchType> typeCaptor = ArgumentCaptor.forClass(BatchType.class);
        when(adapter.generateSettlementRequestFile(typeCaptor.capture(), any()))
                .thenReturn(mockFile);
        when(adapter.transferOutbound(any(), isNull())).thenReturn(mockTransfer);

        scheduler.generateMorningSettlementRequest();

        assertEquals(BatchType.ZP0061, typeCaptor.getValue());
        verify(adapter).transferOutbound(mockFile, null);
    }

    // -----------------------------------------------------------------------
    // ~14:00 KST window: ZP0063
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generateAfternoonSettlementRequest: calls generateSettlementRequestFile(ZP0063)")
    void generateAfternoonSettlement_enabled_callsZp0063() {
        when(properties.isBatchEnabled()).thenReturn(true);
        BatchFile mockFile = new BatchFile(BatchType.ZP0063, LocalDate.now(), 1,
                "data".getBytes(), 0, BigDecimal.ZERO);
        TransferResult mockTransfer = new TransferResult(true, "/out/ZP0063.dat", 4, "sha", 1);

        ArgumentCaptor<BatchType> typeCaptor = ArgumentCaptor.forClass(BatchType.class);
        when(adapter.generateSettlementRequestFile(typeCaptor.capture(), any()))
                .thenReturn(mockFile);
        when(adapter.transferOutbound(any(), isNull())).thenReturn(mockTransfer);

        scheduler.generateAfternoonSettlementRequest();

        assertEquals(BatchType.ZP0063, typeCaptor.getValue());
    }

    // -----------------------------------------------------------------------
    // ~22:00 KST window: ZP0065 + ZP0066
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generatePaymentDetailFile: calls generatePaymentResultFile(ZP0065) when enabled")
    void generatePaymentDetailFile_enabled_callsZp0065() {
        when(properties.isBatchEnabled()).thenReturn(true);
        BatchFile mockFile = new BatchFile(BatchType.ZP0065, LocalDate.now(), 1,
                "data".getBytes(), 0, BigDecimal.ZERO);
        TransferResult mockTransfer = new TransferResult(true, "/out/ZP0065.dat", 4, "sha", 1);

        ArgumentCaptor<BatchType> typeCaptor = ArgumentCaptor.forClass(BatchType.class);
        when(adapter.generatePaymentResultFile(typeCaptor.capture(), any()))
                .thenReturn(mockFile);
        when(adapter.transferOutbound(any(), isNull())).thenReturn(mockTransfer);

        scheduler.generatePaymentDetailFile();

        assertEquals(BatchType.ZP0065, typeCaptor.getValue());
        verify(adapter).transferOutbound(mockFile, null);
    }

    @Test
    @DisplayName("generateRefundDetailFile: calls generateRefundResultFile(ZP0066) when enabled")
    void generateRefundDetailFile_enabled_callsZp0066() {
        when(properties.isBatchEnabled()).thenReturn(true);
        BatchFile mockFile = new BatchFile(BatchType.ZP0066, LocalDate.now(), 1,
                "data".getBytes(), 0, BigDecimal.ZERO);
        TransferResult mockTransfer = new TransferResult(true, "/out/ZP0066.dat", 4, "sha", 1);

        ArgumentCaptor<BatchType> typeCaptor = ArgumentCaptor.forClass(BatchType.class);
        when(adapter.generateRefundResultFile(typeCaptor.capture(), any()))
                .thenReturn(mockFile);
        when(adapter.transferOutbound(any(), isNull())).thenReturn(mockTransfer);

        scheduler.generateRefundDetailFile();

        assertEquals(BatchType.ZP0066, typeCaptor.getValue());
        verify(adapter).transferOutbound(mockFile, null);
    }

    // -----------------------------------------------------------------------
    // Exception-safety: scheduler methods must not propagate exceptions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generatePaymentResult: does not throw when adapter throws internally")
    void generatePaymentResult_adapterThrows_noRethrow() {
        when(properties.isBatchEnabled()).thenReturn(true);
        when(adapter.generatePaymentResultFile(any(), any()))
                .thenThrow(new RuntimeException("simulated failure"));

        // Must not throw — scheduler catches and logs
        scheduler.generatePaymentResult();
    }

    @Test
    @DisplayName("generateMorningSettlementRequest: does not throw when adapter throws")
    void generateMorningSettlement_adapterThrows_noRethrow() {
        when(properties.isBatchEnabled()).thenReturn(true);
        when(adapter.generateSettlementRequestFile(any(), any()))
                .thenThrow(new RuntimeException("simulated failure"));

        scheduler.generateMorningSettlementRequest();
    }
}
