package com.gme.pay.payment.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository over {@code revenue_posting_failures} (Flyway V005, gap T2-1). */
public interface RevenuePostingFailureRepository
        extends JpaRepository<RevenuePostingFailureEntity, Long> {

    /** The replay identity: one row per posting. */
    Optional<RevenuePostingFailureEntity> findByReferenceAndPostingType(String reference,
                                                                       String postingType);

    /** The replay job's working set: postings still missing from revenue-ledger, oldest first. */
    List<RevenuePostingFailureEntity> findByStatusOrderByCreatedAtAscIdAsc(String status);
}
