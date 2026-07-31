package com.gme.pay.settlement.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gme.pay.settlement.batch.SettlementBatchJobService;
import com.gme.pay.settlement.calendar.BusinessCalendar;
import com.gme.pay.settlement.calendar.BusinessDayVerdict;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.runlog.BatchRunAlerter;
import com.gme.pay.settlement.runlog.BatchRunExecutor;
import com.gme.pay.settlement.runlog.BatchRunOutcome;
import com.gme.pay.settlement.runlog.BatchRunRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * The outbound generation scheduler: each window delegates to {@link SettlementBatchJobService}, is gated by
 * the enabled flag, and isolates failures so one failing window neither throws nor blocks the next.
 *
 * <p>Extended for gap <b>T3-4</b>: a failing window must now also be RECORDED in the run ledger and RAISED
 * as an ops alert, and a configured non-business date must be skipped rather than run. The executor is real
 * (it is the unit under test as much as the scheduler is); only the recorder and alerter are mocked, since
 * those are the two observable effects being asserted.
 */
class SettlementGenerationSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SettlementBatchJobService job = mock(SettlementBatchJobService.class);
    private final BatchRunRecorder recorder = mock(BatchRunRecorder.class);
    private final BatchRunAlerter alerter = mock(BatchRunAlerter.class);

    private SettlementGenerationScheduler scheduler(boolean enabled, BusinessCalendar calendar) {
        when(alerter.alertRunFailed(any(), anyString(), anyString(), any(), any()))
                .thenReturn(BatchRunAlerter.AlertOutcome.raised());
        when(alerter.alertCalendarUnverified(anyString(), anyString(), any()))
                .thenReturn(BatchRunAlerter.AlertOutcome.raised());
        return new SettlementGenerationScheduler(enabled, job,
                new BatchRunExecutor(calendar, recorder, alerter));
    }

    private SettlementGenerationScheduler scheduler(boolean enabled) {
        return scheduler(enabled, BusinessCalendar.empty());
    }

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
        SettlementGenerationScheduler s = scheduler(true);

        s.generateMorningRequest();
        s.generateAfternoonRequest();
        s.generateDetailFiles();

        verify(job).runWindow("ZP0061", "MORNING");
        verify(job).runWindow("ZP0063", "AFTERNOON");
        verify(job).runDetailWindow("ZP0065");
        verify(job).runDetailWindow("ZP0066");
    }

    @Test
    @DisplayName("disabled: no window touches the job, but each is RECORDED as SKIPPED_DISABLED")
    void disabledIsNoOp() {
        SettlementGenerationScheduler s = scheduler(false);

        s.generateMorningRequest();
        s.generateAfternoonRequest();
        s.generateDetailFiles();

        verify(job, never()).runWindow(any(), any());
        verify(job, never()).runDetailWindow(any());

        // T3-4: a silently-disabled batch is itself the failure mode this gap is about — a production
        // deployment that forgot the flag produces no settlement files and nothing says so. All four
        // windows (ZP0061, ZP0063, ZP0065, ZP0066) must leave a SKIPPED_DISABLED row.
        ArgumentCaptor<BatchRunOutcome> outcomes = ArgumentCaptor.forClass(BatchRunOutcome.class);
        verify(recorder, org.mockito.Mockito.times(4))
                .recordSkipped(any(), any(), outcomes.capture(), anyString());
        outcomes.getAllValues()
                .forEach(o -> assertEquals(BatchRunOutcome.SKIPPED_DISABLED, o));
    }

    @Test
    @DisplayName("a failing window is never thrown, does not block the next detail file, "
            + "and is BOTH recorded and alerted")
    void failureIsolatedRecordedAndAlerted() {
        when(job.runWindow(any(), any())).thenThrow(new RuntimeException("boom"));
        when(job.runDetailWindow("ZP0065")).thenThrow(new RuntimeException("boom"));
        when(job.runDetailWindow("ZP0066")).thenReturn(batch("D"));
        when(recorder.recordFailure(any(), any(), any())).thenReturn(42L);
        SettlementGenerationScheduler s = scheduler(true);

        assertDoesNotThrow(s::generateMorningRequest);
        assertDoesNotThrow(s::generateDetailFiles);

        // ZP0066 still generated even though ZP0065 threw.
        verify(job).runDetailWindow("ZP0065");
        verify(job).runDetailWindow("ZP0066");

        // T3-4: the two failures (ZP0061 + ZP0065) are durably recorded...
        verify(recorder, org.mockito.Mockito.times(2)).recordFailure(any(), any(), any());
        // ...raised through the ops-alert pipeline...
        verify(alerter).alertRunFailed(eq(42L), eq("ZP0061"), eq("MORNING"), any(), any());
        verify(alerter).alertRunFailed(eq(42L), eq("ZP0065"), eq("DETAIL"), any(), any());
        // ...and the notification outcome is stamped back on the row, so "failed AND nobody was told"
        // is a queryable state rather than a silence.
        verify(recorder, org.mockito.Mockito.times(2)).recordAlertOutcome(eq(42L), any());
    }

    @Test
    @DisplayName("a configured non-business date is SKIPPED, not run, and raises no failure alert")
    void nonBusinessDayIsSkipped() {
        LocalDate today = LocalDate.now(KST);
        BusinessCalendar closedToday = new BusinessCalendar(
                Set.of(today), Set.of(), today.plusYears(1), false);
        SettlementGenerationScheduler s = scheduler(true, closedToday);

        s.generateMorningRequest();
        s.generateDetailFiles();

        verify(job, never()).runWindow(any(), any());
        verify(job, never()).runDetailWindow(any());
        verify(recorder, org.mockito.Mockito.atLeastOnce()).recordSkipped(
                any(), any(), eq(BatchRunOutcome.SKIPPED_NON_BUSINESS_DAY), anyString());
        // A holiday is the calendar working, not an incident.
        verify(alerter, never()).alertRunFailed(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an EMPTY calendar does not block, but every run raises BATCH_CALENDAR_UNVERIFIED")
    void emptyCalendarRunsButWarns() {
        when(job.runWindow(any(), any())).thenReturn(batch("B"));
        SettlementGenerationScheduler s = scheduler(true, BusinessCalendar.empty());

        s.generateMorningRequest();

        // Fail-open: the window still ran...
        verify(job).runWindow("ZP0061", "MORNING");
        // ...but the fact that no holiday check actually happened is reported, not assumed away.
        verify(alerter).alertCalendarUnverified(eq("ZP0061"), eq("MORNING"), any());
        assertEquals(BusinessDayVerdict.UNVERIFIED,
                BusinessCalendar.empty().classify(LocalDate.now(KST)));
    }

    @Test
    @DisplayName("a weekend configured as non-business is skipped without any date being hardcoded")
    void weekendIsConfigurationNotBuiltIn() {
        LocalDate today = LocalDate.now(KST);
        BusinessCalendar noWeekends = new BusinessCalendar(
                Set.of(), Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), today.plusYears(1), false);
        boolean weekend = today.getDayOfWeek() == DayOfWeek.SATURDAY
                || today.getDayOfWeek() == DayOfWeek.SUNDAY;

        assertEquals(weekend ? BusinessDayVerdict.NON_BUSINESS_DAY : BusinessDayVerdict.BUSINESS_DAY,
                noWeekends.classify(today));
        // And with nothing configured at all, the same date is UNVERIFIED rather than assumed open.
        assertEquals(BusinessDayVerdict.UNVERIFIED, BusinessCalendar.empty().classify(today));
    }
}
