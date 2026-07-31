package com.gme.pay.settlement.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** A batch's lines in a stable order — the per-batch detail read (T4-5). */
    List<SettlementLineEntity> findByBatchIdOrderByIdAsc(String batchId);

    /** One merchant's lines on one batch — the partner-facing statement's rows (T4-5). */
    List<SettlementLineEntity> findByBatchIdAndMerchantIdOrderByIdAsc(String batchId, String merchantId);

    /** Every merchant's lines across a set of batches, for a statement over a window (T4-5). */
    List<SettlementLineEntity> findByBatchIdInAndMerchantIdOrderByIdAsc(
            java.util.Collection<String> batchIds, String merchantId);

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

    /**
     * Total magnitude already clawed back for {@code txnRef} across every batch, as a POSITIVE number
     * ({@code null} when nothing has been). T2-6: a partially refunded transaction can be refunded again, so
     * the cumulative {@code refund_amount_krw} grows. A boolean "already clawed back?" gate would net only
     * the FIRST refund and silently swallow every later increment; comparing against this sum lets the
     * settlement window claw back exactly the DELTA that is not yet netted.
     */
    @Query("""
            SELECT COALESCE(SUM(-l.amount), 0)
            FROM SettlementLineEntity l
            WHERE l.txnRef = :txnRef AND l.amount < 0
            """)
    BigDecimal sumClawedBackByTxnRef(@Param("txnRef") String txnRef);
}
