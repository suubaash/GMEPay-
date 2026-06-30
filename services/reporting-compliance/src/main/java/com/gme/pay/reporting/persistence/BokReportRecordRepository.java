package com.gme.pay.reporting.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for {@link BokReportRecordEntity}. {@code existsByTxnId} backs
 * per-transaction idempotency (the table also enforces a UNIQUE on txn_id).
 */
public interface BokReportRecordRepository extends JpaRepository<BokReportRecordEntity, Long> {

    boolean existsByTxnId(long txnId);

    List<BokReportRecordEntity> findByReportDateOrderByTxnIdAsc(LocalDate reportDate);

    List<BokReportRecordEntity> findByFilingId(Long filingId);
}
