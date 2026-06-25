package com.gme.pay.settlement.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gme.pay.settlement.batch.SettlementBatchJobService;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The outbound generation scheduler: each window delegates to {@link SettlementBatchJobService}, is gated by
 * the enabled flag, and isolates failures so one failing window neither throws nor blocks the next.
 */
class SettlementGenerationSchedulerTest {

    private final SettlementBatchJobService job = mock(SettlementBatchJobService.class);

    private static SettlementBatchEntity batch(String id) {
        SettlementBatchEntity b = new SettlementBatchEntity();
        b.setBatchId(id);
        b.setStatus("GENERATED");
        return b;
    }

    @Test
    @DisplayName("enabled: each window calls the matching job entry point with the right file type/window")
    void enabledDelegates() {
        when(job.runWindow(any(), any())).thenReturn(batch("B"));
        when(job.runDetailWindow(any())).thenReturn(batch("D"));
        SettlementGenerationScheduler s = new SettlementGenerationScheduler(true, job);

        s.generateMorningRequest();
        s.generateAfternoonRequest();
        s.generateDetailFiles();

        verify(job).runWindow("ZP0061", "MORNING");
        verify(job).runWindow("ZP0063", "AFTERNOON");
        verify(job).runDetailWindow("ZP0065");
        verify(job).runDetailWindow("ZP0066");
    }

    @Test
    @DisplayName("disabled: no window touches the job")
    void disabledIsNoOp() {
        SettlementGenerationScheduler s = new SettlementGenerationScheduler(false, job);

        s.generateMorningRequest();
        s.generateAfternoonRequest();
        s.generateDetailFiles();

        verify(job, never()).runWindow(any(), any());
        verify(job, never()).runDetailWindow(any());
    }

    @Test
    @DisplayName("a failing window is logged, never thrown, and does not block the next detail file")
    void failureIsolated() {
        when(job.runWindow(any(), any())).thenThrow(new RuntimeException("boom"));
        when(job.runDetailWindow("ZP0065")).thenThrow(new RuntimeException("boom"));
        when(job.runDetailWindow("ZP0066")).thenReturn(batch("D"));
        SettlementGenerationScheduler s = new SettlementGenerationScheduler(true, job);

        assertDoesNotThrow(s::generateMorningRequest);
        assertDoesNotThrow(s::generateDetailFiles);

        // ZP0066 still generated even though ZP0065 threw.
        verify(job).runDetailWindow("ZP0065");
        verify(job).runDetailWindow("ZP0066");
    }
}
