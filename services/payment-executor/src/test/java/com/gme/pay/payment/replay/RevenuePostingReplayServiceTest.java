package com.gme.pay.payment.replay;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * <b>T2-5 replay proof.</b> The gap: {@code revenue_posting_failures} (Flyway V005) made a swallowed
 * revenue-ledger posting durable, but nothing ever drained it, so booked revenue could stay permanently absent
 * from the ledger while every ledger report balanced perfectly.
 *
 * <p>Each test here pins one property the drain has to have, and each one would fail against a naive
 * "loop the pending rows and re-POST" implementation:
 *
 * <ul>
 *   <li>{@link #exactlyOneAttemptPerRowPerSweepAndTheBackoffGatesTheNext()} — a sweep is not a retry loop, and
 *       the backoff is persisted rather than in-process;</li>
 *   <li>{@link #aPostingThatAlreadyLandedIsClearedNotDoubleBooked()} — idempotency against a posting that
 *       succeeded by another route;</li>
 *   <li>{@link #exhaustingTheAttemptBudgetPoisonsAlertsAndStops()} and
 *       {@link #aPermanentRejectionIsPoisonedImmediately()} — the terminal state ALERTS and then stops;</li>
 *   <li>{@link #aRowWithNoPayloadIsPoisonedWithoutCallingRevenueLedger()} — an unreplayable row is not left to
 *       spin;</li>
 *   <li>{@link #aHotPathFailureDoesNotResurrectAPoisonedRow()} — traffic cannot re-arm an exhausted budget.</li>
 * </ul>
 *
 * <p>Runs against the real repository and the real Flyway schema (H2 in PostgreSQL mode), so the V007 columns
 * and the widened status CHECK are exercised rather than assumed. The service is constructed by hand so the
 * attempt budget and backoff can be driven from a fixed clock instead of by waiting.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LedgerOpsRunRecorder.class, LedgerOpsRunExecutor.class,
        RevenuePostingReplayServiceTest.CapturingAlertConfig.class})
class RevenuePostingReplayServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-28T09:00:00Z");
    private static final String REF = "TXN-REPLAY-1";
    private static final String PAYLOAD = "{\"txnRef\":\"TXN-REPLAY-1\",\"payoutMarginUsd\":\"1.25\"}";

    /** Supplies an {@link OpsAlertPipeline} whose publish leg records what was raised. */
    @org.springframework.boot.test.context.TestConfiguration
    static class CapturingAlertConfig {

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

    /** A replay port under the test's control, counting calls so "exactly once" is provable. */
    static final class StubPort implements RevenuePostingReplayPort {
        private final List<String> calls = new ArrayList<>();
        private RevenuePostingReplayOutcome next = RevenuePostingReplayOutcome.transientFailure(503, "down");

        @Override
        public RevenuePostingReplayOutcome replay(String postingType, String jsonPayload) {
            calls.add(postingType + ":" + jsonPayload);
            return next;
        }
    }

    @Autowired
    private RevenuePostingFailureRepository failures;

    @Autowired
    private LedgerOpsRunRepository runs;

    @Autowired
    private LedgerOpsRunExecutor executor;

    private StubPort port;

    @BeforeEach
    void reset() {
        CapturingAlertConfig.RAISED.clear();
        failures.deleteAll();
        runs.deleteAll();
        port = new StubPort();
    }

    private RevenuePostingReplayService serviceWith(int maxAttempts) {
        return new RevenuePostingReplayService(failures, port, executor,
                Clock.fixed(T0, ZoneOffset.UTC), 100, maxAttempts, 60, 21600);
    }

    private RevenuePostingFailureEntity recordFailure() {
        return failures.save(new RevenuePostingFailureEntity(REF,
                RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE, PAYLOAD, "connect refused", T0));
    }

    @Test
    @DisplayName("one attempt per row per sweep; the PERSISTED backoff gates the next one")
    void exactlyOneAttemptPerRowPerSweepAndTheBackoffGatesTheNext() {
        recordFailure();
        RevenuePostingReplayService service = serviceWith(8);

        service.sweepOnce(T0);
        assertThat(port.calls).as("one due row => exactly one POST, never an inner retry loop").hasSize(1);

        RevenuePostingFailureEntity after = failures.findByReferenceAndPostingType(REF,
                RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_PENDING);
        assertThat(after.getAttempts()).as("the EXISTING attempts counter is the bound").isEqualTo(2);
        assertThat(after.getLastAttemptAt()).isEqualTo(T0);
        // attempts is now 2 => backoff = base * 2^(2-1) = 120s. The schedule is on the ROW, so a restart
        // cannot reset it and re-storm a downstream that is still down.
        assertThat(after.getNextAttemptAt()).isEqualTo(T0.plus(Duration.ofSeconds(120)));

        // A sweep before the backoff elapses must not touch the row at all.
        service.sweepOnce(T0.plusSeconds(1));
        assertThat(port.calls).as("a not-yet-due row is not re-attempted").hasSize(1);

        // ...and one after it must.
        service.sweepOnce(T0.plus(Duration.ofSeconds(121)));
        assertThat(port.calls).hasSize(2);
        assertThat(failures.findByReferenceAndPostingType(REF,
                RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE).orElseThrow().getAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("a posting revenue-ledger ALREADY has is cleared, not double-booked, and never re-sent")
    void aPostingThatAlreadyLandedIsClearedNotDoubleBooked() {
        recordFailure();
        // 200 (not 201) is exactly what revenue-ledger answers when its idempotency key already exists —
        // i.e. the money path succeeded on a later attempt and this row is stale.
        port.next = RevenuePostingReplayOutcome.alreadyPresent(200);
        RevenuePostingReplayService service = serviceWith(8);

        RevenuePostingReplayService.ReplaySweepResult first = service.sweepOnce(T0);

        assertThat(first.alreadyPresent()).isEqualTo(1);
        assertThat(first.posted()).isZero();
        assertThat(first.landed()).isEqualTo(1);
        RevenuePostingFailureEntity row = failures.findByReferenceAndPostingType(REF,
                RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_REPLAYED);
        assertThat(row.getReplayedAt()).isEqualTo(T0);
        assertThat(row.getNextAttemptAt()).as("a landed posting has no next attempt").isNull();

        // The idempotency that matters: sweeping again must not send the posting a second time.
        RevenuePostingReplayService.ReplaySweepResult second = service.sweepOnce(T0.plusSeconds(100_000));
        assertThat(second.examined()).isZero();
        assertThat(port.calls).as("exactly one POST for the whole lifetime of the row").hasSize(1);
    }

    @Test
    @DisplayName("exhausting the attempt budget POISONS the row, raises a CRITICAL alert, and stops retrying")
    void exhaustingTheAttemptBudgetPoisonsAlertsAndStops() {
        recordFailure();                    // attempts = 1
        RevenuePostingReplayService service = serviceWith(2);   // so attempts 1 + this attempt == budget

        RevenuePostingReplayService.ReplaySweepResult result = service.sweepOnce(T0);

        assertThat(result.poisoned()).isEqualTo(1);
        assertThat(result.poisonedReferences()).containsExactly(
                REF + "/" + RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE);
        RevenuePostingFailureEntity row = failures.findByReferenceAndPostingType(REF,
                RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
        assertThat(row.getNextAttemptAt()).as("a poisoned row is never due again").isNull();
        assertThat(row.getLastError()).contains("attempt budget exhausted");

        // ALERTS rather than silently giving up — the whole point of a terminal state here.
        assertThat(CapturingAlertConfig.RAISED).hasSize(1);
        OpsAlertPayload alert = CapturingAlertConfig.RAISED.get(0);
        assertThat(alert.alertType()).isEqualTo(RevenuePostingReplayService.ALERT_TYPE_POISON);
        assertThat(alert.severity()).isEqualTo("CRITICAL");
        assertThat(alert.subjectRef()).isEqualTo(REF);
        assertThat(alert.detail()).contains("POISONED").contains("NOT on the ledger");

        // STOPS: no further HTTP call, and no repeat alert on subsequent sweeps.
        service.sweepOnce(T0.plusSeconds(100_000));
        assertThat(port.calls).hasSize(1);
        assertThat(CapturingAlertConfig.RAISED).hasSize(1);
    }

    @Test
    @DisplayName("a 4xx is poisoned immediately — retrying a body the ledger has judged invalid cannot help")
    void aPermanentRejectionIsPoisonedImmediately() {
        recordFailure();
        port.next = RevenuePostingReplayOutcome.permanentRejection(400, "HTTP 400 {\"error_code\":\"BAD\"}");
        RevenuePostingReplayService service = serviceWith(8);   // budget nowhere near exhausted

        service.sweepOnce(T0);

        RevenuePostingFailureEntity row = failures.findByReferenceAndPostingType(REF,
                RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE).orElseThrow();
        assertThat(row.getStatus())
                .as("burning 8 attempts on a deterministic rejection just delays the alert by hours")
                .isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
        assertThat(row.getLastError()).contains("unreplayable").contains("HTTP 400");
        assertThat(CapturingAlertConfig.RAISED).hasSize(1);
    }

    @Test
    @DisplayName("a row with no captured payload is poisoned WITHOUT calling revenue-ledger")
    void aRowWithNoPayloadIsPoisonedWithoutCallingRevenueLedger() {
        failures.save(new RevenuePostingFailureEntity("TXN-NO-PAYLOAD",
                RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL, null, "serialisation failed", T0));

        serviceWith(8).sweepOnce(T0);

        assertThat(port.calls).as("there is nothing to send, so nothing is sent").isEmpty();
        RevenuePostingFailureEntity row = failures.findByReferenceAndPostingType("TXN-NO-PAYLOAD",
                RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
        assertThat(row.getLastError()).contains("no request payload");
        assertThat(CapturingAlertConfig.RAISED).hasSize(1);
    }

    @Test
    @DisplayName("a HOT-PATH re-failure records the occurrence but does NOT resurrect a poisoned row")
    void aHotPathFailureDoesNotResurrectAPoisonedRow() {
        recordFailure();
        serviceWith(2).sweepOnce(T0);        // -> POISON
        assertThat(port.calls).hasSize(1);

        // The same posting fails again on the money path. Flipping it back to PENDING would hand the replay
        // an unbounded budget: the same reference failing daily would re-arm the retries the bound exists to
        // stop, and an operator's ABANDONED decision would be undone by traffic.
        RevenuePostingFailureStore store = new RevenuePostingFailureStore(failures, null);
        store.record(REF, RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE,
                Map.of("txnRef", REF), "revenue-ledger 503 again");

        RevenuePostingFailureEntity row = failures.findByReferenceAndPostingType(REF,
                RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_POISON);
        assertThat(row.getAttempts()).as("the new occurrence is still counted").isEqualTo(3);

        serviceWith(2).sweepOnce(T0.plusSeconds(100_000));
        assertThat(port.calls).as("still terminal, still not retried").hasSize(1);
    }

    @Test
    @DisplayName("a successful sweep is recorded in ledger_ops_runs with what it did")
    void aSuccessfulSweepIsRecordedInTheRunLedger() {
        recordFailure();
        port.next = RevenuePostingReplayOutcome.posted(201);

        serviceWith(8).run(LedgerOpsRunTrigger.OPERATOR, "ops-alice");

        List<LedgerOpsRunEntity> recorded = runs.findAll();
        assertThat(recorded).hasSize(1);
        LedgerOpsRunEntity run = recorded.get(0);
        assertThat(run.getJob()).isEqualTo(LedgerOpsJob.REVENUE_POSTING_REPLAY);
        assertThat(run.getOutcome()).isEqualTo(LedgerOpsRunOutcome.SUCCESS.name());
        assertThat(run.getTriggerSource()).isEqualTo(LedgerOpsRunTrigger.OPERATOR.name());
        assertThat(run.getOperatorId()).as("an operator-triggered run is attributed").isEqualTo("ops-alice");
        assertThat(run.getSummary()).contains("posted=1");
        assertThat(run.getRecordCount()).isEqualTo(1);
    }
}
