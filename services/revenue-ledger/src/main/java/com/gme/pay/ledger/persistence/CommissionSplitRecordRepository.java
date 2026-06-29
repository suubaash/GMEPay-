package com.gme.pay.ledger.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link CommissionSplitRecordEntity} (V005). Insert-only;
 * {@link #findByTxnRef} backs the idempotent replay (one split row per committed transaction).
 */
@Repository
public interface CommissionSplitRecordRepository
        extends JpaRepository<CommissionSplitRecordEntity, Long> {

    Optional<CommissionSplitRecordEntity> findByTxnRef(String txnRef);
}
