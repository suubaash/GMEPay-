package com.gme.pay.txn.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link TransactionEntity} rows.
 *
 * <p>Lives in the {@code persistence} package alongside the entity – kept
 * separate from {@code com.gme.pay.txn.service.TransactionRepository}, which is
 * the domain-layer port over the aggregate.  The in-memory adapter
 * {@code InMemoryTransactionRepository} now delegates to this interface so the
 * service layer is unaffected.
 *
 * <p>V003 adds a paged query for GET /v1/transactions.
 */
@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {

    /**
     * Paged query for GET /v1/transactions with optional filters.
     * All filter parameters are nullable — pass {@code null} to skip that filter.
     *
     * @param fromInstant   lower bound (inclusive) on created_at (null = no lower bound)
     * @param toInstant     upper bound (exclusive) on created_at (null = no upper bound)
     * @param status        filter by status name (null = all statuses)
     * @param partnerId     filter by partner_id (null = all partners)
     * @param pageable      pagination/sort spec
     */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE (:fromInstant  IS NULL OR t.createdAt    >= :fromInstant)
              AND (:toInstant    IS NULL OR t.createdAt    <  :toInstant)
              AND (:status       IS NULL OR t.status       =  :status)
              AND (:partnerId    IS NULL OR t.partnerId    =  :partnerId)
              AND (:txnRef       IS NULL OR t.txnRef       =  :txnRef)
              AND (:schemeTxnRef IS NULL OR t.schemeTxnRef =  :schemeTxnRef)
              AND (:merchantId   IS NULL OR t.merchantId   =  :merchantId)
            """)
    Page<TransactionEntity> findByFilters(
            @Param("fromInstant")  Instant fromInstant,
            @Param("toInstant")    Instant toInstant,
            @Param("status")       String status,
            @Param("partnerId")    Long partnerId,
            @Param("txnRef")       String txnRef,
            @Param("schemeTxnRef") String schemeTxnRef,
            @Param("merchantId")   String merchantId,
            Pageable pageable);

    /**
     * Ops stuck-transaction sweep (STUCK_TXN / UNCERTAIN_AGED alerts). Returns rows whose status
     * is in {@code sweepStatuses} and whose {@code updatedAt} is older than {@code stuckBefore}
     * (i.e. they have not moved for longer than the configured threshold). Ordered by
     * {@code updatedAt} so the oldest / most-aged surface first.
     */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.status IN :sweepStatuses
              AND t.updatedAt < :stuckBefore
            ORDER BY t.updatedAt ASC
            """)
    List<TransactionEntity> findStuck(
            @Param("stuckBefore") Instant stuckBefore,
            @Param("sweepStatuses") List<String> sweepStatuses);

    /**
     * Returns non-terminal transactions whose {@code createdAt} is older than
     * {@code expiryBefore} and whose status is in the provided set of sweepable statuses.
     *
     * <p>Only CREATED and PENDING_DEBIT are sweepable (both can legally transition to FAILED
     * per {@link com.gme.pay.txn.domain.statemachine.TransactionTransitions}).
     * Terminal states (APPROVED, FAILED, CANCELLED) are never returned here.
     *
     * @param expiryBefore   upper-exclusive bound on createdAt (i.e. now minus timeout)
     * @param sweepStatuses  set of status names to sweep (e.g. ["CREATED","PENDING_DEBIT"])
     */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.createdAt < :expiryBefore
              AND t.status IN :sweepStatuses
            """)
    List<TransactionEntity> findExpiredNonTerminal(
            @Param("expiryBefore") Instant expiryBefore,
            @Param("sweepStatuses") List<String> sweepStatuses);

    /**
     * V007: committed-FX projection feed (GET /v1/transactions/fx-committed). Returns committed
     * rows (committed_at populated) in the half-open instant window {@code [from, to)}, optionally
     * narrowed to one partner. Ordered by commit time so callers page deterministically.
     *
     * @param from      lower bound (inclusive) on committed_at
     * @param to        upper bound (exclusive) on committed_at
     * @param partnerId filter by partner_id (null = all partners)
     */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.committedAt IS NOT NULL
              AND t.committedAt >= :from
              AND t.committedAt <  :to
              AND (:partnerId IS NULL OR t.partnerId = :partnerId)
            ORDER BY t.committedAt ASC
            """)
    List<TransactionEntity> findCommittedFx(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("partnerId") Long partnerId);

    /**
     * V007: refund query (GET /v1/transactions/refunded?refundedOn). Returns rows whose
     * {@code refunded_at} falls within the half-open instant window {@code [from, to)} for the
     * requested calendar day. Ordered by refund time.
     */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.refundedAt IS NOT NULL
              AND t.refundedAt >= :from
              AND t.refundedAt <  :to
            ORDER BY t.refundedAt ASC
            """)
    List<TransactionEntity> findRefundedOn(
            @Param("from") Instant from,
            @Param("to") Instant to);
}
