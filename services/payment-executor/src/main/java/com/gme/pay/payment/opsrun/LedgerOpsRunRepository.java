package com.gme.pay.payment.opsrun;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data repository over {@code ledger_ops_runs} (Flyway V008, gap T2-5). */
public interface LedgerOpsRunRepository extends JpaRepository<LedgerOpsRunEntity, Long> {

    /** "Did last night's run happen, and did it work?" — newest first, for one job. */
    List<LedgerOpsRunEntity> findByJobOrderByStartedAtDescIdDesc(String job, Pageable pageable);

    /**
     * The single most recent run of one job, whatever its outcome — the read behind missed-run
     * detection ({@code MissedLedgerOpsRunMonitor}).
     *
     * <p>Outcome is deliberately not filtered: a FAILED run <em>happened</em>, and it already raised
     * its own {@code LEDGER_OPS_RUN_FAILED} alert. What this read is looking for is silence.
     *
     * <p>Served by {@code idx_ledger_ops_runs_job_started (job, started_at DESC)} from V008.
     */
    Optional<LedgerOpsRunEntity> findFirstByJobOrderByStartedAtDescIdDesc(String job);

    /**
     * Retention pruner: drop runs older than the cutoff, <b>except the newest run of each job</b>.
     *
     * <p>The exception is load-bearing, not tidiness. Missed-run detection answers "when did this job
     * last run?" from this table; a pruner that deleted the last surviving row for a job that has been
     * silent for longer than the retention window would erase the very evidence of the silence, and
     * the two mechanisms would quietly cancel each other out. Keeping one row per job costs a handful
     * of rows forever and keeps "it last ran a year ago" distinguishable from "it has no history".
     */
    @Modifying
    @Query("""
            DELETE FROM LedgerOpsRunEntity r
            WHERE r.startedAt < :cutoff
              AND EXISTS (
                    SELECT 1 FROM LedgerOpsRunEntity newer
                    WHERE newer.job = r.job
                      AND (newer.startedAt > r.startedAt
                           OR (newer.startedAt = r.startedAt AND newer.id > r.id)))
            """)
    int deleteOlderThanKeepingLatestPerJob(@Param("cutoff") Instant cutoff);

    /** Newest runs across every job — the ops overview. */
    List<LedgerOpsRunEntity> findAllByOrderByStartedAtDescIdDesc(Pageable pageable);

    /** Every failure, newest first — the incident query. */
    List<LedgerOpsRunEntity> findByOutcomeOrderByStartedAtDescIdDesc(String outcome, Pageable pageable);
}
