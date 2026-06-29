package com.gme.pay.settlement.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link SettlementBatchEntity}.
 * Owned by settlement-reconciliation; no other service accesses this table.
 */
@Repository
public interface SettlementBatchRepository extends JpaRepository<SettlementBatchEntity, String> {

    List<SettlementBatchEntity> findByPartnerIdAndBusinessDate(String partnerId, LocalDate businessDate);

    List<SettlementBatchEntity> findByStatus(String status);

    /** Outbound-batch idempotency key (V006 unique index): one batch per file_type + date + window. */
    Optional<SettlementBatchEntity> findByFileTypeAndBusinessDateAndSettlementWindow(
            String fileType, LocalDate businessDate, String settlementWindow);
}
