package com.gme.pay.settlement.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Data repository for {@link SettlementLineEntity}.
 * Owned by settlement-reconciliation; no other service accesses this table.
 */
@Repository
public interface SettlementLineRepository extends JpaRepository<SettlementLineEntity, Long> {

    List<SettlementLineEntity> findByBatchId(String batchId);

    List<SettlementLineEntity> findByBatchIdAndMatched(String batchId, boolean matched);

    /** Remove a batch's lines so an outbound generation re-run (PENDING/ERROR batch) is clean. */
    void deleteByBatchId(String batchId);

    /**
     * True if a settled PAYMENT line (positive {@code amount}) already exists for this txn in any batch
     * — i.e. the merchant was already paid out for it. A refund is only clawed back when this holds;
     * a same-day approve→refund (never paid) has no such line and correctly nets to zero.
     */
    boolean existsByTxnRefAndAmountGreaterThan(String txnRef, BigDecimal amount);

    /**
     * True if a REFUND clawback line (negative {@code amount}) already exists for this txn — guards
     * against clawing the same refund twice across the morning/afternoon windows (idempotency marker).
     */
    boolean existsByTxnRefAndAmountLessThan(String txnRef, BigDecimal amount);
}
