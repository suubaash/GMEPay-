package com.gme.pay.payment.opsrun;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Keeps {@code ledger_ops_runs} bounded (T2-5 caveat (e)) — the counterpart to
 * {@code OpsAlertRetentionSweeper}, built to the same shape on purpose.
 *
 * <p>The table gains a row per replay sweep (every 5 minutes), per day-close, per FX-exposure
 * measurement and now per operator requeue. Nothing ever removed one. That is roughly 105 000 rows a
 * year from the replay sweeper alone, each carrying up to 4 KB of stack excerpt on a failure — an
 * append-only audit table with no expiry is a slow disk-full, and the one it fills is the database the
 * money path writes to.
 *
 * <h2>Retention is an engineering default, and an owner must confirm it</h2>
 *
 * <p><b>{@code gmepay.ledger-ops.runs.retention-days} defaults to 365, and that number is not a
 * records-retention commitment.</b> It is set longer than {@code ops_alerts}' 90 days because this is
 * an execution record for financial jobs rather than an incident feed — "did the day-close run every
 * night last year?" is a question an auditor can reasonably ask, and one a 90-day window cannot answer.
 * It is not set to the multi-year horizon that statutory transaction-record obligations imply, because
 * <b>this table is not a transaction record</b>: it holds no amounts, no counterparties and no
 * postings, only whether a job ran. If the owner or compliance concludes the run ledger is in scope for
 * a statutory retention period, this is one property, and the pruner will honour it as it stands.
 *
 * <h2>Time-bounded, not count-bounded</h2>
 *
 * <p>Same reasoning as {@code ops_alerts}: a count bound silently discards the oldest evidence exactly
 * when volume is highest, which is when it is most likely to be interesting.
 *
 * <h2>The newest run of each job is never pruned</h2>
 *
 * <p>{@link LedgerOpsRunRepository#deleteOlderThanKeepingLatestPerJob} keeps one row per job whatever
 * its age, because {@link MissedLedgerOpsRunMonitor} reads exactly that row to answer "when did this
 * job last run?". Without the exception the two features would cancel out: a job silent for longer than
 * the retention window would have its last trace deleted, and "silent for 400 days" would become
 * indistinguishable from "no history".
 *
 * <p>Enabled by default; {@code gmepay.ledger-ops.runs.prune-enabled=false} keeps runs forever.
 */
@Component
@ConditionalOnProperty(name = "gmepay.ledger-ops.runs.prune-enabled", havingValue = "true",
        matchIfMissing = true)
public class LedgerOpsRunRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(LedgerOpsRunRetentionSweeper.class);

    private final LedgerOpsRunRepository repository;
    private final Clock clock;
    private final Duration retention;

    public LedgerOpsRunRetentionSweeper(
            LedgerOpsRunRepository repository,
            Clock clock,
            @Value("${gmepay.ledger-ops.runs.retention-days:365}") int retentionDays) {
        this.repository = repository;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays > 0 ? retentionDays : 365);
    }

    /**
     * A delete is idempotent, so a duplicate prune is harmless in outcome. It is locked anyway, for the
     * reason {@code OpsAlertRetentionSweeper} states: N replicas each issuing the same bulk DELETE is N
     * times the contention for zero benefit, and "which jobs are locked" should not be a per-job
     * judgement someone re-makes whenever a job body changes.
     */
    @Scheduled(fixedDelayString = "${gmepay.ledger-ops.runs.prune-interval-ms:21600000}",
            initialDelayString = "${gmepay.ledger-ops.runs.prune-initial-delay-ms:600000}")
    @SchedulerLock(name = "LedgerOpsRunRetentionSweeper_prune",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void prune() {
        try {
            pruneNow();
        } catch (RuntimeException e) {
            // A retention failure must never take the scheduler down; the table just grows until fixed.
            log.error("ledger_ops_runs retention prune failed: {}", e.toString());
        }
    }

    /** Deletes expired runs and returns the count. Transactional so the bulk delete has a unit. */
    @Transactional
    public int pruneNow() {
        Instant cutoff = Instant.now(clock).minus(retention);
        int deleted = repository.deleteOlderThanKeepingLatestPerJob(cutoff);
        if (deleted > 0) {
            log.info("pruned {} ledger_ops_runs rows started before {} ({} day retention; the newest "
                    + "run of each job is always kept so missed-run detection keeps its baseline)",
                    deleted, cutoff, retention.toDays());
        }
        return deleted;
    }

    /** The configured window, for the test that pins the shipped default. */
    public Duration retention() {
        return retention;
    }
}
