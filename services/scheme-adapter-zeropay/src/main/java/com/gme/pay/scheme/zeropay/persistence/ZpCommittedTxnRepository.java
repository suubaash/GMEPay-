package com.gme.pay.scheme.zeropay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Committed real-time transaction store ({@code zp_committed_txns}).
 *
 * <p>Read by {@code ZpPersistenceBatchDataPort} to build the daily ZP00xx outbound files;
 * written by {@code ZpCommittedTxnRecorder} on the real-time payment/refund path.</p>
 */
public interface ZpCommittedTxnRepository extends JpaRepository<ZpCommittedTxnEntity, Long> {

    /** All captured txns of one kind for a business date, oldest first (file order). */
    List<ZpCommittedTxnEntity> findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
            LocalDate businessDate, String txnKind);

    /** All captured txns for a business date (both kinds), for settlement aggregation. */
    List<ZpCommittedTxnEntity> findByBusinessDateOrderByMerchantIdAscTxnTimeAsc(LocalDate businessDate);
}
