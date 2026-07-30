package com.gme.pay.settlement.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** All batches for a business date — used by the operator recon re-run's settlementDate scope. */
    List<SettlementBatchEntity> findByBusinessDate(LocalDate businessDate);

    List<SettlementBatchEntity> findByStatus(String status);

    /** Outbound-batch idempotency key (V006 unique index): one batch per file_type + date + window. */
    Optional<SettlementBatchEntity> findByFileTypeAndBusinessDateAndSettlementWindow(
            String fileType, LocalDate businessDate, String settlementWindow);

    // ---- T4-5: date-RANGED reads of the PERSISTED batches ------------------------------------
    //
    // The only settlement read surface used to be GET /v1/settlements, which recomputed per-merchant
    // figures from unbatched approved transactions for ONE date and never touched these tables. So a
    // statement could not be produced for a period, and the batch an operator was looking at was not
    // the batch that had actually been booked and filed. These are the queries that fixed that.

    /** Every persisted batch whose business date falls in [from, to], newest first. */
    List<SettlementBatchEntity> findByBusinessDateBetweenOrderByBusinessDateDescBatchIdAsc(
            LocalDate fromInclusive, LocalDate toInclusive);

    /** As above, narrowed to one counterparty ({@code partner_id}, e.g. ZEROPAY). */
    List<SettlementBatchEntity> findByPartnerIdAndBusinessDateBetweenOrderByBusinessDateDescBatchIdAsc(
            String partnerId, LocalDate fromInclusive, LocalDate toInclusive);

    /**
     * The batches a given MERCHANT actually appears on, over a window — the partner-facing statement's
     * spine. Deliberately keyed off {@code settlement_lines.merchant_id} and NOT
     * {@code settlement_batches.partner_id}: the batch's partner_id is the COUNTERPARTY (ZEROPAY), so
     * filtering statements by it would return one partner every merchant's rows, or nothing at all.
     */
    @Query("""
            SELECT b FROM SettlementBatchEntity b
            WHERE b.businessDate BETWEEN :fromInclusive AND :toInclusive
              AND EXISTS (SELECT 1 FROM SettlementLineEntity l
                          WHERE l.batchId = b.batchId AND l.merchantId = :merchantId)
            ORDER BY b.businessDate DESC, b.batchId ASC
            """)
    List<SettlementBatchEntity> findForMerchantInWindow(@Param("merchantId") String merchantId,
                                                        @Param("fromInclusive") LocalDate fromInclusive,
                                                        @Param("toInclusive") LocalDate toInclusive);
}
