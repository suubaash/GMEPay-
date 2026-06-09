package com.gme.pay.settlement.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data repository for {@link SettlementBatchEntity}.
 * Owned by settlement-reconciliation; no other service accesses this table.
 */
@Repository
public interface SettlementBatchRepository extends JpaRepository<SettlementBatchEntity, String> {

    List<SettlementBatchEntity> findByPartnerIdAndBusinessDate(String partnerId, LocalDate businessDate);

    List<SettlementBatchEntity> findByStatus(String status);
}
