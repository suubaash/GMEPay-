package com.gme.pay.scheme.zeropay.ops;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository over the {@code zp_batch_runs} ledger (Flyway V004). Every read is bounded — the table grows
 * by six rows a day forever and the ops question is always "the most recent N", never "all of history".
 */
public interface ZpBatchRunRepository extends JpaRepository<ZpBatchRunEntity, Long> {

    List<ZpBatchRunEntity> findByOrderByFinishedAtDesc(Pageable pageable);

    List<ZpBatchRunEntity> findByOutcomeOrderByFinishedAtDesc(String outcome, Pageable pageable);

    /**
     * Whether this batch type has ALREADY completed successfully for this date — the duplicate-run guard
     * the manual trigger consults before regenerating a file KFTC may already hold.
     */
    boolean existsByBatchTypeAndBusinessDateAndOutcome(String batchType, LocalDate businessDate,
                                                       String outcome);

    Optional<ZpBatchRunEntity> findFirstByBatchTypeAndBusinessDateOrderByFinishedAtDesc(
            String batchType, LocalDate businessDate);

    /** Last successful run per batch type — the "did last night's six windows happen?" check. */
    @Query("""
            select r.batchType, max(r.finishedAt)
              from ZpBatchRunEntity r
             where r.outcome = :outcome
             group by r.batchType
            """)
    List<Object[]> findLastSuccessPerType(@Param("outcome") String outcome);
}
