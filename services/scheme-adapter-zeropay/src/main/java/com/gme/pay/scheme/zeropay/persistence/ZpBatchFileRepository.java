package com.gme.pay.scheme.zeropay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Batch file registry ({@code zp_batch_files}).
 */
public interface ZpBatchFileRepository extends JpaRepository<ZpBatchFileEntity, Long> {

    /** Natural-key lookup; backed by {@code uq_zp_batch_files_type_date_seq}. */
    Optional<ZpBatchFileEntity> findByFileTypeAndBusinessDateAndSequenceNo(
            String fileType, LocalDate businessDate, int sequenceNo);

    List<ZpBatchFileEntity> findByBusinessDateAndDirection(LocalDate businessDate, String direction);
}
