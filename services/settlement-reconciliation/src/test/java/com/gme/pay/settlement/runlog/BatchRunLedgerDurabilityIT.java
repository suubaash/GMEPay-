package com.gme.pay.settlement.runlog;

import com.gme.pay.settlement.calendar.BusinessCalendar;
import com.gme.pay.settlement.calendar.BusinessDayVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The load-bearing claim of gap <b>T3-4</b>, tested against the REAL Spring context and REAL JPA/H2:
 * <b>a failed-run record survives the rollback of the transaction whose failure it describes.</b>
 *
 * <p>This is the whole point of {@link BatchRunRecorder}'s {@link Propagation#REQUIRES_NEW}. The settlement
 * job runs in one {@code @Transactional}; when it throws, that transaction is marked rollback-only and
 * unwound, which is why nothing used to survive to say the run had happened. A ledger write that joined that
 * transaction would be discarded with it — so asserting the annotation exists proves nothing, and only
 * driving a real rollback around a real recorder call does.
 *
 * <p>{@code SettlementBatchJobService} itself is not exercised here; a {@link TransactionTemplate} that
 * throws reproduces exactly the condition (an outer transaction rolling back around the recorder) with none
 * of the cross-service port setup, which keeps the test about durability rather than about settlement.
 */
@SpringBootTest
class BatchRunLedgerDurabilityIT {

    @Autowired
    private BatchRunRecorder recorder;

    @Autowired
    private BatchRunRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static BatchRunRecorder.RunKey key(String fileType, String window, LocalDate date) {
        return BatchRunRecorder.RunKey.generation(
                fileType, window, date, BatchRunTrigger.SCHEDULER, BusinessDayVerdict.UNVERIFIED);
    }

    @Test
    @DisplayName("a FAILED row written inside a rolling-back transaction still commits (REQUIRES_NEW)")
    void failureRowSurvivesOuterRollback() {
        LocalDate date = LocalDate.of(2026, 7, 29);
        RuntimeException boom = new IllegalStateException("simulated settlement failure");

        // An outer transaction that writes the ledger row and then FAILS — exactly the shape of
        // SettlementBatchJobService.runWindow blowing up after createOrGet.
        assertThrows(IllegalStateException.class, () -> transactionTemplate.execute(status -> {
            recorder.recordFailure(key("ZP0061", "MORNING", date), Instant.now(), boom);
            throw boom;
        }));

        List<BatchRunEntity> rows = repository.findByBusinessDateOrderByFinishedAtDesc(date);
        assertEquals(1, rows.size(),
                "the failed-run row must survive the rollback — this is the entire T3-4 fix");
        BatchRunEntity row = rows.get(0);
        assertEquals(BatchRunOutcome.FAILED.name(), row.getOutcome());
        assertEquals("ZP0061", row.getFileType());
        assertEquals("MORNING", row.getSettlementWindow());
        assertEquals(IllegalStateException.class.getName(), row.getFailureClass());
        assertEquals("simulated settlement failure", row.getFailureMessage());
        assertNotNull(row.getFailureTrace(), "a stack excerpt must be captured for diagnosis");
        assertTrue(row.getFailureTrace().contains("BatchRunLedgerDurabilityIT"),
                "the excerpt should identify the throw site");
        assertEquals(BusinessDayVerdict.UNVERIFIED.name(), row.getCalendarVerdict(),
                "every row records what the calendar knew, so an unverified business day is provable");
    }

    @Test
    @DisplayName("the alert outcome is stamped on the same row: one row answers 'what failed' AND 'who knew'")
    void alertOutcomeIsStampedOnTheRow() {
        LocalDate date = LocalDate.of(2026, 7, 28);
        Long id = recorder.recordFailure(key("ZP0063", "AFTERNOON", date), Instant.now(),
                new RuntimeException("nope"));
        assertNotNull(id);

        recorder.recordAlertOutcome(id, BatchRunAlerter.AlertOutcome.failed("webhook 500"));

        BatchRunEntity row = repository.findById(id).orElseThrow();
        assertEquals(BatchRunAlerter.AlertOutcome.FAILED, row.getAlertStatus());
        assertEquals("webhook 500", row.getAlertError());
    }

    @Test
    @DisplayName("recording never throws, even on an absurdly long failure message")
    void recordingIsWidthSafeAndNeverThrows() {
        LocalDate date = LocalDate.of(2026, 7, 27);
        String huge = "x".repeat(50_000);

        Long id = recorder.recordFailure(key("ZP0065", "DETAIL", date), Instant.now(),
                new RuntimeException(huge));

        assertNotNull(id, "an oversized message must be truncated, not lost");
        BatchRunEntity row = repository.findById(id).orElseThrow();
        assertEquals(BatchRunEntity.MAX_MESSAGE, row.getFailureMessage().length());
        assertTrue(row.getFailureTrace().length() <= BatchRunEntity.MAX_TRACE);
    }

    @Test
    @DisplayName("the success ledger answers 'did last night's run happen?' and the duplicate-run guard")
    void successLedgerBacksTheRerunGuard() {
        LocalDate date = LocalDate.of(2026, 7, 26);

        assertFalse(repository.existsByFileTypeAndSettlementWindowAndBusinessDateAndOutcome(
                "ZP0061", "MORNING", date, BatchRunOutcome.SUCCESS.name()));

        recorder.recordSuccess(key("ZP0061", "MORNING", date), Instant.now(), "BATCH-1", "GENERATED", 7);

        assertTrue(repository.existsByFileTypeAndSettlementWindowAndBusinessDateAndOutcome(
                        "ZP0061", "MORNING", date, BatchRunOutcome.SUCCESS.name()),
                "this exists-check is what makes the re-run API refuse rather than duplicate");

        List<Object[]> lastSuccess =
                repository.findLastSuccessPerWindow(BatchRunOutcome.SUCCESS.name());
        assertTrue(lastSuccess.stream().anyMatch(r -> "ZP0061".equals(r[0]) && "MORNING".equals(r[1])),
                "last-success-per-window is derived from the ledger, so it cannot drift from it");
    }

    @Test
    @DisplayName("ledger reads are bounded — there is no way to pull the whole table")
    void readsAreBounded() {
        LocalDate date = LocalDate.of(2026, 7, 25);
        for (int i = 0; i < 5; i++) {
            recorder.recordSuccess(key("ZP0061", "MORNING", date), Instant.now(), "B" + i, "GENERATED", i);
        }
        assertEquals(2, repository.findByOrderByFinishedAtDesc(PageRequest.of(0, 2)).size());
    }

    @Test
    @DisplayName("an empty calendar bean is wired and classifies today as UNVERIFIED, not BUSINESS_DAY")
    @Transactional
    void shippedCalendarIsEmptyAndHonest(@Autowired BusinessCalendar calendar) {
        assertFalse(calendar.hasData(),
                "the shipped calendar must contain NO holiday data — populating it is an operator input");
        assertEquals(BusinessDayVerdict.UNVERIFIED, calendar.classify(LocalDate.now()));
        assertFalse(calendar.isFailClosed(), "default must be fail-open so batches still run");
    }
}
