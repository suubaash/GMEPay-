package com.gme.pay.payment.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data repository over {@code revenue_posting_failures} (Flyway V005 + V007, gaps T2-1 / T2-5). */
public interface RevenuePostingFailureRepository
        extends JpaRepository<RevenuePostingFailureEntity, Long> {

    /** The replay identity: one row per posting. */
    Optional<RevenuePostingFailureEntity> findByReferenceAndPostingType(String reference,
                                                                       String postingType);

    /** The replay job's working set: postings still missing from revenue-ledger, oldest first. */
    List<RevenuePostingFailureEntity> findByStatusOrderByCreatedAtAscIdAsc(String status);

    /**
     * The replay sweep's batch (gap T2-5): PENDING rows whose backoff has elapsed, oldest first, bounded by
     * {@code pageable}.
     *
     * <p>A null {@code next_attempt_at} counts as due — that is how rows written before V007 (and any row a
     * failed schedule-stamp left unscheduled) get picked up instead of being stranded forever.
     */
    @Query("""
            select f from RevenuePostingFailureEntity f
             where f.status = 'PENDING'
               and (f.nextAttemptAt is null or f.nextAttemptAt <= :now)
             order by f.createdAt asc, f.id asc
            """)
    List<RevenuePostingFailureEntity> findDueForReplay(@Param("now") Instant now, Pageable pageable);

    /** How many rows sit in each status — the ops "what is outstanding" headline. */
    @Query("select f.status, count(f) from RevenuePostingFailureEntity f group by f.status")
    List<Object[]> countByStatus();

    /**
     * Per-(status, postingType) counts plus the oldest row's age in each bucket — enough for an operator (and
     * for the day-close report's {@code REVENUE_POSTINGS_OUTSTANDING} variance) to see not just how many
     * postings are missing but how long they have been missing.
     */
    @Query("""
            select f.status, f.postingType, count(f), min(f.createdAt)
              from RevenuePostingFailureEntity f
             group by f.status, f.postingType
             order by f.status asc, f.postingType asc
            """)
    List<Object[]> outstandingBreakdown();

    /** Rows in one status, newest-first, bounded — the ops list view. */
    List<RevenuePostingFailureEntity> findByStatusOrderByUpdatedAtDesc(String status, Pageable pageable);

    /** Every row, newest-first, bounded — the ops list view with no status filter. */
    List<RevenuePostingFailureEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}
