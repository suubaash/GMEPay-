package com.gme.pay.settlement.runlog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository over the {@code batch_runs} ledger (Flyway V012).
 *
 * <p>Every read is bounded by a {@link Pageable} — there is no unbounded {@code findAll} on this table by
 * design, because it grows by ~8 rows a day forever and the ops query behind it is always "the most recent
 * N", never "all of history".
 */
public interface BatchRunRepository extends JpaRepository<BatchRunEntity, Long> {

    /** Newest runs first, any outcome — the ops "what has the batch been doing" view. */
    List<BatchRunEntity> findByOrderByFinishedAtDesc(Pageable pageable);

    /** Newest runs first, filtered to one outcome (typically {@code FAILED}). */
    List<BatchRunEntity> findByOutcomeOrderByFinishedAtDesc(String outcome, Pageable pageable);

    /** Every run for one business date, newest first — the "did last night happen?" query. */
    List<BatchRunEntity> findByBusinessDateOrderByFinishedAtDesc(LocalDate businessDate);

    /**
     * Whether a given window for a given business date has ALREADY completed successfully. This is the
     * duplicate-run guard the operator re-run API consults before doing anything: a re-run of a window that
     * already succeeded is REFUSED (409) rather than silently re-generating a file the counterparty has
     * already received.
     */
    boolean existsByFileTypeAndSettlementWindowAndBusinessDateAndOutcome(
            String fileType, String settlementWindow, LocalDate businessDate, String outcome);

    /** The most recent run of one window on one date, whatever its outcome. */
    Optional<BatchRunEntity> findFirstByFileTypeAndSettlementWindowAndBusinessDateOrderByFinishedAtDesc(
            String fileType, String settlementWindow, LocalDate businessDate);

    /**
     * The most recent SUCCESSFUL run of each window, as (fileType, settlementWindow, lastSuccess) triples —
     * the "last-success timestamp" the COO audit asked for, in one round trip. Derived rather than stored so
     * it can never drift from the ledger.
     */
    @org.springframework.data.jpa.repository.Query("""
            select r.fileType, r.settlementWindow, max(r.finishedAt)
              from BatchRunEntity r
             where r.outcome = :outcome
             group by r.fileType, r.settlementWindow
            """)
    List<Object[]> findLastSuccessPerWindow(@Param("outcome") String outcome);
}
