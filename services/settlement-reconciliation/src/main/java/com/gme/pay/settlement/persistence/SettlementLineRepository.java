package com.gme.pay.settlement.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link SettlementLineEntity}.
 * Owned by settlement-reconciliation; no other service accesses this table.
 */
@Repository
public interface SettlementLineRepository extends JpaRepository<SettlementLineEntity, Long> {

    List<SettlementLineEntity> findByBatchId(String batchId);

    List<SettlementLineEntity> findByBatchIdAndMatched(String batchId, boolean matched);
}
