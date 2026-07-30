package com.gme.pay.payment.opsrun;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository over {@code ledger_ops_runs} (Flyway V008, gap T2-5). */
public interface LedgerOpsRunRepository extends JpaRepository<LedgerOpsRunEntity, Long> {

    /** "Did last night's run happen, and did it work?" — newest first, for one job. */
    List<LedgerOpsRunEntity> findByJobOrderByStartedAtDescIdDesc(String job, Pageable pageable);

    /** Newest runs across every job — the ops overview. */
    List<LedgerOpsRunEntity> findAllByOrderByStartedAtDescIdDesc(Pageable pageable);

    /** Every failure, newest first — the incident query. */
    List<LedgerOpsRunEntity> findByOutcomeOrderByStartedAtDescIdDesc(String outcome, Pageable pageable);
}
