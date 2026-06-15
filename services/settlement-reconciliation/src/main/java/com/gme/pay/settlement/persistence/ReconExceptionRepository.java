package com.gme.pay.settlement.persistence;

import com.gme.pay.settlement.exception.ExceptionStatus;
import com.gme.pay.settlement.recon.MatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link ReconExceptionEntity}.
 * Owned by settlement-reconciliation; no other service accesses this table.
 */
@Repository
public interface ReconExceptionRepository extends JpaRepository<ReconExceptionEntity, Long> {

    List<ReconExceptionEntity> findByBatchId(String batchId);

    List<ReconExceptionEntity> findByBatchIdAndMatchStatus(String batchId, MatchStatus matchStatus);

    long countByBatchIdAndMatchStatusNot(String batchId, MatchStatus matchStatus);

    // --- exception API query methods (V005 ops lifecycle fields) ---

    List<ReconExceptionEntity> findByExceptionStatus(ExceptionStatus exceptionStatus);

    List<ReconExceptionEntity> findByBatchIdAndExceptionStatus(
            String batchId, ExceptionStatus exceptionStatus);

    List<ReconExceptionEntity> findByExceptionStatusAndMatchStatus(
            ExceptionStatus exceptionStatus, MatchStatus matchStatus);
}
