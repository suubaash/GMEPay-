package com.gme.pay.payment.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.payment.alert.OpsAlertEvent;
import com.gme.pay.payment.alert.OpsAlertPipeline;
import com.gme.pay.payment.opsrun.LedgerOpsJob;
import com.gme.pay.payment.opsrun.LedgerOpsRunEntity;
import com.gme.pay.payment.opsrun.LedgerOpsRunExecutor;
import com.gme.pay.payment.opsrun.LedgerOpsRunOutcome;
import com.gme.pay.payment.opsrun.LedgerOpsRunRecorder;
import com.gme.pay.payment.opsrun.LedgerOpsRunRepository;
import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import com.gme.pay.payment.persistence.RevenuePostingFailureEntity;
import com.gme.pay.payment.persistence.RevenuePostingFailureRepository;
import com.gme.pay.payment.persistence.RevenuePostingFailureStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * <b>T2-5 follow-up proof</b>: POISON is no longer a one-way door.
 *
 * <p>The gap this closes was found by T3-12. Revenue-ledger returned 406 on two journal endpoints because of a
 * server-side content-negotiation defect; the replay client called 406 a permanent rejection; the sweep
 * poisoned every {@code ROUNDING_RESIDUAL} and {@code REVERSAL_JOURNAL} row on the FIRST attempt. POISON is
 * terminal with no requeue path in the codebase, so a bug fixed within the week had permanently orphaned real
 * booked revenue — recoverable only by editing {@code revenue_posting_failures} in production by hand.
 *
 * <p>Runs against the real repository, the real Flyway schema (H2 in PostgreSQL mode) and the real
 * {@link LedgerOpsRunExecutor}, so V012's widened {@code ck_ledger_ops_runs_job} is genuinely exercised — an
 * audit row that violated the CHECK would fail here rather than the first time an operator used the endpoint.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LedgerOpsRunRecorder.class, LedgerOpsRunExecutor.class,
        RevenuePostingRequeueServiceTest.SilentAlertConfig.class})
class RevenuePostingRequeueServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-28T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-29T09:00:00Z");
    private static final String PAYLOAD = "{\"reference\":\"TXN-1\",\"residual\":\"0.4000\",\"currency\":\"KRW\"}";

    /** The requeue raises no alerts of its own; this keeps the pipeline real without a broker. */
    @org.springframework.boot.test.context.TestConfiguration
    static class SilentAlertConfig {

        static final List<OpsAlertPayload> RAISED = new ArrayList<>();

        @org.springframework.context.annotation.Bean
        OpsAlertPipeline opsAlertPipeline() {
            EventPublisher capturing = (DomainEvent event) -> {
                if (event instanceof OpsAlertEvent alert) {
                    RAISED.add(alert.getPayload());
                }
            };
            return new OpsAlertPipeline(capturing);
        }
    }

    @Autowired
    private RevenuePostingFailureRepository failures;

    @Autowired
    private LedgerOpsRunRepository runs;

    @Autowired
    private LedgerOpsRunExecutor executor;

    private RevenuePostingRequeueService service;

    @BeforeEach
    void reset() {
        SilentAlertConfig.RAISED.clear();
        failures.deleteAll();
        runs.deleteAll();
        service = new RevenuePostingRequeueService(failures, executor, Clock.fixed(T1, ZoneOffset.UTC), 1000);
    }

    /** A row in exactly the state the 406 defect left it: POISON on the first sweep, payload intact. */
    private RevenuePostingFailureEntity poisoned(String ref, String type) {
        RevenuePostingFailureEntity row =
                new RevenuePostingFailureEntity(ref, type, PAYLOAD, "connect refused", T0);
        row.poison("unreplayable: HTTP 406 (no body)", T0, true);
        return failures.save(row);
    }

    @Test
    @DisplayName("the 406 case: POISON rows of the named types go back to PENDING with attempts reset to 0")
    void requeueMovesPoisonBackToPendingWithAFreshBudget() {
        RevenuePostingFailureEntity residual =
                poisoned("TXN-1", RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL);
        poisoned("TXN-2", RevenuePostingFailureStore.TYPE_REVERSAL_JOURNAL);
        // A different type, equally poisoned: it must be left alone, because the filter is the whole point.
        poisoned("TXN-3", RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE);

        var result = service.requeue(
                List.of(RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL,
                        RevenuePostingFailureStore.TYPE_REVERSAL_JOURNAL),
                null, "revenue-ledger 406 content-negotiation defect fixed (T3-12)", "ops.kim");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.value().requeued()).isEqualTo(2);
        assertThat(result.value().matched()).isEqualTo(2);

        RevenuePostingFailureEntity after = failures.findById(residual.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_PENDING);
        assertThat(after.getAttempts())
                .as("attempts MUST reset: leaving it at the exhausted value would poison the row again on "
                        + "the very next sweep, which is a no-op dressed as a fix")
                .isZero();
        assertThat(after.getNextAttemptAt())
                .as("due immediately — the operator requeued because the blocker is believed fixed")
                .isEqualTo(T1);
        assertThat(after.getLastError())
                .as("the row itself records that it was requeued, by whom is in the run ledger")
                .contains("REQUEUED by operator")
                .contains("T3-12");

        // The untargeted type is untouched.
        assertThat(failures.findByReferenceAndPostingType("TXN-3",
                        RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE).orElseThrow().getStatus())
                .isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
    }

    @Test
    @DisplayName("idempotent: a second identical requeue moves 0 rows and changes nothing")
    void requeueIsIdempotent() {
        poisoned("TXN-1", RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL);
        List<String> types = List.of(RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL);

        assertThat(service.requeue(types, null, "first", "ops.kim").value().requeued()).isEqualTo(1);

        var second = service.requeue(types, null, "again", "ops.kim");
        assertThat(second.value().requeued())
                .as("the rows are PENDING now, so the POISON filter finds nothing — a nervous operator "
                        + "running the command twice must be a no-op, not a corruption")
                .isZero();
        assertThat(second.value().matched()).isZero();

        RevenuePostingFailureEntity after = failures.findByReferenceAndPostingType("TXN-1",
                RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL).orElseThrow();
        assertThat(after.getLastError())
                .as("the second call must not overwrite the first requeue's reason")
                .contains("first")
                .doesNotContain("again");
    }

    @Test
    @DisplayName("every requeue writes an attributed ledger_ops_runs row (V012's widened CHECK holds)")
    void requeueIsAudited() {
        poisoned("TXN-1", RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL);

        service.requeue(List.of(RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL), null,
                "406 fixed", "ops.kim");

        List<LedgerOpsRunEntity> rows = runs.findAll();
        assertThat(rows).hasSize(1);
        LedgerOpsRunEntity run = rows.get(0);
        assertThat(run.getJob()).isEqualTo(LedgerOpsJob.REVENUE_POSTING_REQUEUE);
        assertThat(run.getOutcome()).isEqualTo(LedgerOpsRunOutcome.SUCCESS.name());
        assertThat(run.getTriggerSource()).isEqualTo(LedgerOpsRunTrigger.OPERATOR.name());
        assertThat(run.getOperatorId())
                .as("a requeue is only ever a human act, so it is always attributed")
                .isEqualTo("ops.kim");
        assertThat(run.getRecordCount()).isEqualTo(1);
        assertThat(run.getSummary())
                .as("the run ledger is where the discarded attempt history and the WHY survive")
                .contains("requeued=1")
                .contains("406 fixed");
    }

    @Test
    @DisplayName("an unfiltered requeue is refused — 'everything' is a different decision")
    void requeueWithNoSelectorIsRejected() {
        poisoned("TXN-1", RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL);

        assertThatThrownBy(() -> service.requeue(null, null, "oops", "ops.kim"))
                .isInstanceOf(RevenuePostingRequeueService.RequeueRejectedException.class)
                .hasMessageContaining("postingTypes and/or ids");

        assertThat(failures.findByReferenceAndPostingType("TXN-1",
                        RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL).orElseThrow().getStatus())
                .isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
        assertThat(runs.findAll())
                .as("a rejected selector is the caller's error, not a FAILED run worth paging about")
                .isEmpty();
    }

    @Test
    @DisplayName("a mistyped posting type is refused, not silently ignored")
    void unknownPostingTypeIsRejected() {
        assertThatThrownBy(() -> service.requeue(List.of("ROUNDING_RESIDUALS"), null, "typo", "ops.kim"))
                .isInstanceOf(RevenuePostingRequeueService.RequeueRejectedException.class)
                .hasMessageContaining("unknown postingType");
    }

    @Test
    @DisplayName("targeting by id works, and overlapping selectors requeue a row once")
    void idsAreSupportedAndOverlapIsDeduplicated() {
        RevenuePostingFailureEntity a = poisoned("TXN-1", RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL);
        RevenuePostingFailureEntity b = poisoned("TXN-9", RevenuePostingFailureStore.TYPE_COMMISSION_SPLIT);

        var result = service.requeue(
                List.of(RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL),
                List.of(a.getId(), b.getId()), "mixed selectors", "ops.kim");

        assertThat(result.value().matched())
                .as("row a is selected by BOTH the type filter and the id list; it must appear once")
                .isEqualTo(2);
        assertThat(result.value().requeued()).isEqualTo(2);
    }

    @Test
    @DisplayName("a POISON row with no captured payload is skipped, not requeued into a doomed retry")
    void unreplayableRowsAreSkipped() {
        RevenuePostingFailureEntity noPayload = new RevenuePostingFailureEntity(
                "TXN-NOPAY", RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL, null, "no payload", T0);
        noPayload.poison("unreplayable: no request payload was captured", T0, false);
        failures.save(noPayload);

        var result = service.requeue(List.of(RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL), null,
                "406 fixed", "ops.kim");

        assertThat(result.value().requeued()).isZero();
        assertThat(result.value().skippedUnreplayable())
                .as("no number of attempts can send a body that was never captured")
                .isEqualTo(1);
        assertThat(failures.findById(noPayload.getId()).orElseThrow().getStatus())
                .isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
    }

    @Test
    @DisplayName("a bulk type requeue never overturns an operator's ABANDONED decision")
    void abandonedRowsAreNotRequeuedByTypeFilter() {
        RevenuePostingFailureEntity abandoned = new RevenuePostingFailureEntity("TXN-ABD",
                RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL, PAYLOAD, "written off", T0);
        abandoned.setStatus(RevenuePostingFailureEntity.STATUS_ABANDONED);
        failures.save(abandoned);

        var result = service.requeue(List.of(RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL), null,
                "406 fixed", "ops.kim");

        assertThat(result.value().matched())
                .as("the type filter selects POISON only, so ABANDONED is never even a candidate")
                .isZero();
        assertThat(failures.findById(abandoned.getId()).orElseThrow().getStatus())
                .isEqualTo(RevenuePostingFailureEntity.STATUS_ABANDONED);

        // ...but naming its id explicitly is an operator deliberately reversing their own call, and that
        // row still is not moved: only POISON is requeueable, whatever the selector.
        var byId = service.requeue(null, List.of(abandoned.getId()), "reconsidered", "ops.kim");
        assertThat(byId.value().matched()).isEqualTo(1);
        assertThat(byId.value().skippedNotPoison()).isEqualTo(1);
        assertThat(byId.value().requeued()).isZero();
    }

    @Test
    @DisplayName("a requeued row is picked up by the very next sweep")
    void aRequeuedRowIsDueForTheNextSweep() {
        poisoned("TXN-1", RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL);
        service.requeue(List.of(RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL), null, "406 fixed", "ops");

        // The end-to-end point of the whole feature: requeue makes it visible to findDueForReplay again.
        assertThat(failures.findDueForReplay(T1, org.springframework.data.domain.PageRequest.of(0, 100)))
                .as("a requeued row that the sweep cannot see is a requeue that did nothing")
                .hasSize(1);
    }
}
