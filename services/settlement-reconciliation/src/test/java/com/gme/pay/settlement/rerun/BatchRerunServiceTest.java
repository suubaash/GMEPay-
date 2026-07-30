package com.gme.pay.settlement.rerun;

import com.gme.pay.settlement.batch.SettlementBatchJobService;
import com.gme.pay.settlement.calendar.BusinessCalendar;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.runlog.BatchRunAlerter;
import com.gme.pay.settlement.runlog.BatchRunEntity;
import com.gme.pay.settlement.runlog.BatchRunExecutor;
import com.gme.pay.settlement.runlog.BatchRunOutcome;
import com.gme.pay.settlement.runlog.BatchRunRecorder;
import com.gme.pay.settlement.runlog.BatchRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The operator re-run for outbound generation (gap <b>T3-4</b>: "no rerun tooling"), focused on the property
 * the brief actually asked for — <b>safe to invoke twice: idempotent, and refusing rather than duplicating
 * when a successful run already exists.</b>
 */
class BatchRerunServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 29);

    private final SettlementBatchJobService job = mock(SettlementBatchJobService.class);
    private final BatchRunRepository runRepository = mock(BatchRunRepository.class);
    private final BatchRunRecorder recorder = mock(BatchRunRecorder.class);
    private final BatchRunAlerter alerter = mock(BatchRunAlerter.class);

    private BatchRerunService service() {
        when(alerter.alertRunFailed(any(), anyString(), anyString(), any(), any()))
                .thenReturn(BatchRunAlerter.AlertOutcome.raised());
        when(alerter.alertCalendarUnverified(anyString(), anyString(), any()))
                .thenReturn(BatchRunAlerter.AlertOutcome.raised());
        return new BatchRerunService(job,
                new BatchRunExecutor(BusinessCalendar.empty(), recorder, alerter),
                runRepository);
    }

    private static SettlementBatchEntity batch(String id, String status) {
        SettlementBatchEntity b = new SettlementBatchEntity();
        b.setBatchId(id);
        b.setStatus(status);
        b.setRecordCount(3);
        return b;
    }

    private static BatchRerunRequest req(String fileType, boolean force) {
        return new BatchRerunRequest(fileType, null, DATE, "ops-alice", "05:00 failed", force);
    }

    @Test
    @DisplayName("re-runs a named date — not 'today' — through the date-parameterised entry point")
    void rerunsTheNamedDate() {
        when(job.runWindow("ZP0061", "MORNING", DATE)).thenReturn(batch("B1", "GENERATED"));
        BatchRerunResponse res = service().rerun(req("ZP0061", false));

        verify(job).runWindow("ZP0061", "MORNING", DATE);
        assertEquals(BatchRunOutcome.SUCCESS.name(), res.outcome());
        assertEquals("B1", res.batchId());
        assertEquals(DATE, res.businessDate());
        assertEquals("ops-alice", res.operatorId());
    }

    @Test
    @DisplayName("REFUSES when a successful run already exists — does not silently duplicate the file")
    void refusesWhenAlreadySucceeded() {
        BatchRunEntity existing = new BatchRunEntity();
        existing.setBatchId("B-ALREADY");
        when(runRepository.existsByFileTypeAndSettlementWindowAndBusinessDateAndOutcome(
                "ZP0061", "MORNING", DATE, BatchRunOutcome.SUCCESS.name())).thenReturn(true);
        when(runRepository.findFirstByFileTypeAndSettlementWindowAndBusinessDateOrderByFinishedAtDesc(
                "ZP0061", "MORNING", DATE)).thenReturn(Optional.of(existing));

        assertThrows(BatchRerunAlreadySucceededException.class,
                () -> service().rerun(req("ZP0061", false)));

        // The point of refusing: the job is never touched, so no second file/lines/outbox event.
        verify(job, never()).runWindow(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("force=true overrides the refusal, and the underlying job is still idempotent")
    void forceOverridesButDomainStaysIdempotent() {
        when(runRepository.existsByFileTypeAndSettlementWindowAndBusinessDateAndOutcome(
                anyString(), anyString(), any(), anyString())).thenReturn(true);
        // The job returns the EXISTING batch unchanged — createOrGet + the PENDING-only guard.
        when(job.runWindow("ZP0061", "MORNING", DATE)).thenReturn(batch("B1", "GENERATED"));

        BatchRerunResponse res = service().rerun(req("ZP0061", true));

        verify(job).runWindow("ZP0061", "MORNING", DATE);
        assertEquals(BatchRunOutcome.SUCCESS.name(), res.outcome());
        assertEquals("GENERATED", res.batchStatus());
    }

    @Test
    @DisplayName("invoking twice with force is safe: same batch back, two ledger rows, one file")
    void twiceIsSafe() {
        when(job.runWindow("ZP0061", "MORNING", DATE)).thenReturn(batch("B1", "GENERATED"));
        BatchRerunService s = service();

        BatchRerunResponse first = s.rerun(req("ZP0061", true));
        BatchRerunResponse second = s.rerun(req("ZP0061", true));

        assertEquals(first.batchId(), second.batchId());
        // Two attempts, two ledger rows — a double invocation is visible rather than indistinguishable.
        verify(recorder, org.mockito.Mockito.times(2)).recordSuccess(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("detail file types route to runDetailWindow with the DETAIL window")
    void detailFilesRouteCorrectly() {
        when(job.runDetailWindow("ZP0066", DATE)).thenReturn(batch("D1", "GENERATED"));
        BatchRerunResponse res = service().rerun(req("ZP0066", false));

        verify(job).runDetailWindow("ZP0066", DATE);
        assertEquals("DETAIL", res.settlementWindow());
    }

    @Test
    @DisplayName("a failing re-run is recorded and alerted, and reports FAILED rather than throwing")
    void failingRerunIsRecordedAndReported() {
        when(job.runWindow("ZP0061", "MORNING", DATE)).thenThrow(new RuntimeException("still broken"));
        when(recorder.recordFailure(any(), any(), any())).thenReturn(9L);

        BatchRerunResponse res = service().rerun(req("ZP0061", false));

        assertEquals(BatchRunOutcome.FAILED.name(), res.outcome());
        verify(recorder).recordFailure(any(), any(), any());
        verify(alerter).alertRunFailed(eq(9L), eq("ZP0061"), eq("MORNING"), eq(DATE), any());
    }

    @Test
    @DisplayName("malformed requests are rejected, not guessed at")
    void malformedRequestsRejected() {
        BatchRerunService s = service();

        // No date: "today" is not the case an operator needs.
        assertThrows(IllegalArgumentException.class, () -> s.rerun(
                new BatchRerunRequest("ZP0061", null, null, "ops", "why", false)));
        // Inbound recon file types belong to the OTHER re-run endpoint.
        assertThrows(IllegalArgumentException.class, () -> s.rerun(
                new BatchRerunRequest("ZP0062", null, DATE, "ops", "why", false)));
        // A window that contradicts the file type would key the ledger row wrongly.
        assertThrows(IllegalArgumentException.class, () -> s.rerun(
                new BatchRerunRequest("ZP0061", "AFTERNOON", DATE, "ops", "why", false)));
        // Unknown file type.
        assertThrows(IllegalArgumentException.class, () -> s.rerun(
                new BatchRerunRequest("NOPE", null, DATE, "ops", "why", false)));
    }

    @Test
    @DisplayName("the operator and reason reach the ledger, so who/why is recorded with the run")
    void operatorAttributionIsRecorded() {
        when(job.runWindow("ZP0061", "MORNING", DATE)).thenReturn(batch("B1", "GENERATED"));
        service().rerun(req("ZP0061", false));

        org.mockito.ArgumentCaptor<BatchRunRecorder.RunKey> captor =
                org.mockito.ArgumentCaptor.forClass(BatchRunRecorder.RunKey.class);
        verify(recorder).recordSuccess(captor.capture(), any(), any(), any(), any());
        assertEquals("ops-alice", captor.getValue().operatorId());
        assertEquals("05:00 failed", captor.getValue().reason());
        assertEquals(com.gme.pay.settlement.runlog.BatchRunTrigger.OPERATOR_RERUN,
                captor.getValue().trigger());
    }
}
