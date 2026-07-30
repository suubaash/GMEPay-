package com.gme.pay.payment.opsrun;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

/**
 * <b>T2-5 caveat (e): the {@code ledger_ops_runs} retention pruner.</b>
 *
 * <p>The table gained a row every five minutes from the replay sweeper and nothing ever removed one.
 * These tests run the real JPQL against a real H2 database with the full Flyway set applied, because
 * the two properties that matter here are both properties of the <em>statement</em>: that it deletes
 * only what is past the window, and that it never deletes the newest run of a job.
 *
 * <p>That second property is not tidiness. {@link MissedLedgerOpsRunMonitor} answers "when did this
 * job last run?" from exactly that row. A pruner without the exception would delete the last trace of
 * a job that has been silent longer than the retention window — so the two features would cancel out,
 * and the longer a job had been broken the less evidence would survive.
 *
 * <p>Runs against its own named in-memory database so committed rows from other slice tests in this
 * module cannot perturb a test that counts deletions.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ledgeropsretention;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                + "DATABASE_TO_LOWER=TRUE"
})
class LedgerOpsRunRetentionTest {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
    private static final int RETENTION_DAYS = 365;

    @Autowired
    private LedgerOpsRunRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private LedgerOpsRunRetentionSweeper sweeper;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        entityManager.flush();
        sweeper = new LedgerOpsRunRetentionSweeper(
                repository, Clock.fixed(NOW, ZoneOffset.UTC), RETENTION_DAYS);
    }

    private LedgerOpsRunEntity run(String job, Instant startedAt) {
        LedgerOpsRunEntity row = new LedgerOpsRunEntity();
        row.setJob(job);
        row.setOutcome(LedgerOpsRunOutcome.SUCCESS.name());
        row.setTriggerSource(LedgerOpsRunTrigger.SCHEDULER.name());
        row.setBusinessDate(LocalDate.of(2026, 1, 1));
        row.setSummary("replayed=0");
        row.setStartedAt(startedAt);
        row.setFinishedAt(startedAt);
        return repository.save(row);
    }

    /** Bulk JPQL deletes bypass the persistence context; clear it before reading back. */
    private List<LedgerOpsRunEntity> reread() {
        entityManager.flush();
        entityManager.clear();
        return repository.findAll();
    }

    @Test
    @DisplayName("only runs beyond the retention window are deleted")
    void deletesOnlyBeyondTheWindow() {
        Instant justInside = NOW.minus(Duration.ofDays(RETENTION_DAYS - 1));
        Instant justOutside = NOW.minus(Duration.ofDays(RETENTION_DAYS + 1));

        Long keptEdge = run(LedgerOpsJob.REVENUE_POSTING_REPLAY, justInside).getId();
        Long keptRecent = run(LedgerOpsJob.REVENUE_POSTING_REPLAY, NOW.minus(Duration.ofMinutes(5)))
                .getId();
        run(LedgerOpsJob.REVENUE_POSTING_REPLAY, justOutside);
        run(LedgerOpsJob.REVENUE_POSTING_REPLAY, NOW.minus(Duration.ofDays(900)));

        assertThat(sweeper.pruneNow())
                .as("both rows past the 365-day window, and neither of the two inside it")
                .isEqualTo(2);

        assertThat(reread()).extracting(LedgerOpsRunEntity::getId)
                .as("a row one day inside the window must survive — the boundary is the whole point "
                        + "of a time-bounded retention rather than a count-bounded one")
                .containsExactlyInAnyOrder(keptEdge, keptRecent);
    }

    @Test
    @DisplayName("the newest run of a job is never pruned, however old it is")
    void neverPrunesTheLastTraceOfAJob() {
        // A job that stopped running 500 days ago: every one of its rows is past the window.
        Long onlySurvivor = run(LedgerOpsJob.DAY_CLOSE, NOW.minus(Duration.ofDays(500))).getId();
        run(LedgerOpsJob.DAY_CLOSE, NOW.minus(Duration.ofDays(600)));
        run(LedgerOpsJob.DAY_CLOSE, NOW.minus(Duration.ofDays(700)));

        assertThat(sweeper.pruneNow()).isEqualTo(2);

        assertThat(reread()).extracting(LedgerOpsRunEntity::getId)
                .as("if the last row went too, 'this job has been silent for 500 days' would become "
                        + "'this job has no history' — and missed-run detection would lose the very "
                        + "evidence it exists to surface")
                .containsExactly(onlySurvivor);

        assertThat(repository.findFirstByJobOrderByStartedAtDescIdDesc(LedgerOpsJob.DAY_CLOSE))
                .as("...and the monitor's own read still finds it")
                .isPresent();
    }

    @Test
    @DisplayName("the exemption is per job, not one row overall")
    void keepsTheNewestRunOfEveryJobIndependently() {
        Long newestReplay = run(LedgerOpsJob.REVENUE_POSTING_REPLAY,
                NOW.minus(Duration.ofDays(400))).getId();
        Long newestClose = run(LedgerOpsJob.DAY_CLOSE, NOW.minus(Duration.ofDays(500))).getId();
        Long newestFx = run(LedgerOpsJob.FX_EXPOSURE, NOW.minus(Duration.ofDays(600))).getId();
        run(LedgerOpsJob.REVENUE_POSTING_REPLAY, NOW.minus(Duration.ofDays(800)));
        run(LedgerOpsJob.DAY_CLOSE, NOW.minus(Duration.ofDays(800)));
        run(LedgerOpsJob.FX_EXPOSURE, NOW.minus(Duration.ofDays(800)));

        assertThat(sweeper.pruneNow()).isEqualTo(3);
        assertThat(reread()).extracting(LedgerOpsRunEntity::getId)
                .containsExactlyInAnyOrder(newestReplay, newestClose, newestFx);
    }

    @Test
    @DisplayName("an empty table and a table with nothing expired both prune zero rows")
    void pruningIsANoOpWhenNothingHasExpired() {
        assertThat(sweeper.pruneNow()).isZero();

        run(LedgerOpsJob.FX_EXPOSURE, NOW.minus(Duration.ofDays(10)));
        run(LedgerOpsJob.FX_EXPOSURE, NOW.minus(Duration.ofDays(20)));
        assertThat(sweeper.pruneNow()).isZero();
        assertThat(reread()).hasSize(2);
    }

    @Test
    @DisplayName("the shipped retention default is the documented 365 days")
    void shippedRetentionIsPinned() throws Exception {
        java.util.Properties shipped = org.springframework.core.io.support.PropertiesLoaderUtils
                .loadProperties(new org.springframework.core.io.ClassPathResource(
                        "application.properties"));
        // Pinned so the value cannot drift away from the reasoning recorded next to it. It is an
        // ENGINEERING default, not a records-retention commitment — an owner still has to confirm it.
        assertThat(shipped.getProperty("gmepay.ledger-ops.runs.retention-days")).isEqualTo("365");
        assertThat(shipped.getProperty("gmepay.ledger-ops.runs.prune-enabled"))
                .as("a retention pruner that has to be switched on is one nobody switched on")
                .isEqualTo("true");
        assertThat(new LedgerOpsRunRetentionSweeper(repository, Clock.systemUTC(), 365).retention())
                .isEqualTo(Duration.ofDays(365));
        assertThat(new LedgerOpsRunRetentionSweeper(repository, Clock.systemUTC(), 0).retention())
                .as("a nonsensical 0 must not mean 'delete everything'")
                .isEqualTo(Duration.ofDays(365));
    }
}
