package com.gme.pay.scheme.zeropay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Staged ZP0011/ZP0012 record store ({@code zp_staged_records}).
 */
public interface ZpStagedRecordRepository extends JpaRepository<ZpStagedRecordEntity, Long> {

    /** All staged lines of one batch file, in file order. */
    List<ZpStagedRecordEntity> findByBatchFileIdOrderByLineNumberAsc(Long batchFileId);

    /** Reconciliation match-key lookup (SCH-06 §5.3); backed by {@code idx_zp_staged_records_match_key}. */
    List<ZpStagedRecordEntity> findByZeropayTxnRefAndTxnDate(String zeropayTxnRef, LocalDate txnDate);
}
