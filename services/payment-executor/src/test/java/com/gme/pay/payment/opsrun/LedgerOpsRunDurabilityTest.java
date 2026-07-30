package com.gme.pay.payment.opsrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.payment.alert.OpsAlertEvent;
import com.gme.pay.payment.alert.OpsAlertPipeline;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.Commit;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>T2-5 scheduler-durability proof</b> for {@code ledger_ops_runs} (Flyway V008).
 *
 * <p>The property under test is the one T3-4 identified for settlement's {@code batch_runs} and that these three
 * jobs need for the same reason: <em>a failed run must leave evidence</em>. Without it, "the day-close did not
 * run" and "the day-close ran and blew up" look identical from the outside — which is how a silent gap survives
 * for weeks.
 *
 * <p>Proved from three independent directions, so a regression cannot slip through any one of them:
 *
 * <ol>
 *   <li>{@link #aFailedRunIsRecordedWithItsDiagnosisAndAlerted()} — the FAILED row carries the exception class,
 *       message and a bounded stack excerpt, and the alert outcome is stamped on the same row;</li>
 *   <li>{@link #theRecorderCommitsOnItsOwnTransaction()} — the {@code REQUIRES_NEW} propagation that makes the
 *       row survive the rollback of the work it describes is asserted on the annotation itself. That is the
 *       load-bearing detail: on the caller's transaction the evidence would be rolled back with the failure;</li>
 *   <li>{@link #recordedRunsSurviveARestart()} — committed rows are re-read from a <b>freshly built Spring
 *       context</b> (the previous one, its {@code EntityManagerFactory} and every bean that could have been
 *       holding runs in a field having been destroyed by {@code @DirtiesContext}).</li>
 * </ol>
 *
 * <p>Rows are tagged with a sentinel business date so the committed data cannot affect other slice tests sharing
 * this JVM's named H2 instance.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import({LedgerOpsRunRecorder.class, LedgerOpsRunExecutor.class,
        LedgerOpsRunDurabilityTest.CapturingAlertConfig.class})
class LedgerOpsRunDurabilityTest {

    /** Unique to this test so committed rows are invisible to every other query in the module. */
    private static final LocalDate PROBE_DATE = LocalDate.of(2099, 12, 31);

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

    @Autowired
    private LedgerOpsRunRepository repository;

    @Autowired
    private LedgerOpsRunExecutor executor;

    @Test
    @Order(1)
    @DisplayName("a FAILED run is recorded with its diagnosis, and the alert outcome is stamped on the row")
    @Commit
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void aFailedRunIsRecordedWithItsDiagnosisAndAlerted() {
        CapturingAlertConfig.RAISED.clear();

        LedgerOpsRunExecutor.RunResult<String> result = executor.execute(
                LedgerOpsRunRecorder.RunKey.scheduled(LedgerOpsJob.DAY_CLOSE, PROBE_DATE),
                () -> {
                    throw new IllegalStateException("revenue-ledger unreachable");
                },
                null);

        // The executor never throws for a business reason — the scheduler thread must survive.
        assertThat(result.outcome()).isEqualTo(LedgerOpsRunOutcome.FAILED);
        assertThat(result.failure()).isInstanceOf(IllegalStateException.class);
        assertThat(result.runId()).isNotNull();

        LedgerOpsRunEntity row = repository.findById(result.runId()).orElseThrow();
        assertThat(row.getJob()).isEqualTo(LedgerOpsJob.DAY_CLOSE);
        assertThat(row.getBusinessDate()).isEqualTo(PROBE_DATE);
        assertThat(row.getOutcome()).isEqualTo(LedgerOpsRunOutcome.FAILED.name());
        assertThat(row.getTriggerSource()).isEqualTo(LedgerOpsRunTrigger.SCHEDULER.name());
        assertThat(row.getFailureClass()).isEqualTo(IllegalStateException.class.getName());
        assertThat(row.getFailureMessage()).isEqualTo("revenue-ledger unreachable");
        assertThat(row.getFailureTrace())
                .as("a bounded stack excerpt identifies the throw site without filling the disk")
                .contains("IllegalStateException")
                .hasSizeLessThanOrEqualTo(4000);
        assertThat(row.getAlertStatus())
                .as("one row answers both 'what failed' and 'was anyone told'")
                .isEqualTo(LedgerOpsRunRecorder.ALERT_EMITTED);

        assertThat(CapturingAlertConfig.RAISED).hasSize(1);
        OpsAlertPayload alert = CapturingAlertConfig.RAISED.get(0);
        assertThat(alert.alertType()).isEqualTo(LedgerOpsRunExecutor.ALERT_TYPE_RUN_FAILED);
        assertThat(alert.severity()).isEqualTo("CRITICAL");
        assertThat(alert.detail()).contains(LedgerOpsJob.DAY_CLOSE).contains("ledger_ops_runs.id=");
    }

    @Test
    @Order(2)
    @DisplayName("the recorder commits on its OWN transaction, so a FAILED row survives the caller's rollback")
    void theRecorderCommitsOnItsOwnTransaction() throws Exception {
        // This is the detail the whole table depends on. The work these jobs wrap is @Transactional; when it
        // throws, Spring marks that transaction rollback-only and unwinds it. A ledger write on the SAME
        // transaction would be rolled back with the failure it was recording, and a write that joined a
        // still-rollback-marked transaction would fail with UnexpectedRollbackException. Asserted on the
        // annotation rather than staged, because propagation is a static property and a staged nested rollback
        // proves it only for the one call shape the test happens to build.
        for (String method : List.of("recordFailure", "recordSuccess", "recordSkipped",
                "recordAlertOutcome")) {
            Transactional tx = java.util.Arrays.stream(LedgerOpsRunRecorder.class.getMethods())
                    .filter(m -> m.getName().equals(method))
                    .map(m -> m.getAnnotation(Transactional.class))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            method + " must be @Transactional(REQUIRES_NEW) or a failed run's evidence "
                                    + "rolls back with the failure"));
            assertThat(tx.propagation())
                    .as("%s must suspend the caller's transaction and commit on its own connection", method)
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    @Test
    @Order(3)
    @DisplayName("recorded runs are re-read from a FRESHLY BUILT context — they are on disk, not in a bean")
    void recordedRunsSurviveARestart() {
        List<LedgerOpsRunEntity> found = repository.findAll().stream()
                .filter(r -> PROBE_DATE.equals(r.getBusinessDate()))
                .toList();

        assertThat(found)
                .as("the run recorded before the context teardown must still be there")
                .hasSize(1);
        assertThat(found.get(0).getOutcome()).isEqualTo(LedgerOpsRunOutcome.FAILED.name());
        assertThat(found.get(0).getFailureMessage()).isEqualTo("revenue-ledger unreachable");

        repository.deleteAll(found);   // leave the shared H2 instance as it was found
    }
}
