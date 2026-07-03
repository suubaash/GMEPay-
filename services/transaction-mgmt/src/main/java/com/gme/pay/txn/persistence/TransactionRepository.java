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
            WHERE (:fromInstant  IS NULL OR t.createdAt     >= :fromInstant)
              AND (:toInstant    IS NULL OR t.createdAt     <  :toInstant)
              AND (:status       IS NULL OR t.status        =  :status)
              AND (:partnerId    IS NULL OR t.partnerId     =  :partnerId)
              AND (:txnRef       IS NULL OR t.txnRef        =  :txnRef)
              AND (:schemeTxnRef IS NULL OR t.schemeTxnRef  =  :schemeTxnRef)
              AND (:merchantId   IS NULL OR t.merchantId    =  :merchantId)
              AND (:userRef      IS NULL OR t.userRef       =  :userRef)
              AND (:reference    IS NULL OR t.partnerTxnRef =  :reference)
              AND (:schemeId     IS NULL OR t.schemeId      =  :schemeId)
            """)
    Page<TransactionEntity> findByFilters(
            @Param("fromInstant")  Instant fromInstant,
            @Param("toInstant")    Instant toInstant,
            @Param("status")       String status,
            @Param("partnerId")    Long partnerId,
            @Param("txnRef")       String txnRef,
            @Param("schemeTxnRef") String schemeTxnRef,
            @Param("merchantId")   String merchantId,
            @Param("userRef")      String userRef,
            @Param("reference")    String reference,
            @Param("schemeId")     String schemeId,
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

    // -------------------------------------------------------------------------
    // Delivery-dashboard aggregates (GET /v1/transactions/stats). Read-only,
    // single grouped queries over the existing rows in the half-open instant
    // window {@code [from, to)} — never load all rows into the app.
    // -------------------------------------------------------------------------

    /**
     * Per-status counts in the window (one row per distinct status present). The service
     * folds these into totals / approved / declined so the math lives in one place and the
     * "approved" / "declined" status sets stay a single source of truth.
     */
    @Query("""
            SELECT t.status AS bucket, COUNT(t) AS cnt
            FROM TransactionEntity t
            WHERE t.createdAt >= :from AND t.createdAt < :to
            GROUP BY t.status
            """)
    List<CountByBucket> countByStatus(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Per-(partner, status) counts in the window, grouped by {@code partner_ref} (always
     * populated, unlike the nullable numeric {@code partner_id}). The service pivots each
     * partner's status rows into total / approved / declined + success rate.
     */
    @Query("""
            SELECT t.partnerRef AS grp, t.status AS bucket, COUNT(t) AS cnt
            FROM TransactionEntity t
            WHERE t.createdAt >= :from AND t.createdAt < :to
            GROUP BY t.partnerRef, t.status
            """)
    List<GroupStatusCount> countByPartnerAndStatus(
            @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Per-(corridor, status) counts in the window. Corridor = {@code scheme_id} (the QR
     * scheme / network column that exists on the row); rows with a null scheme_id collapse
     * under a single null group the service labels {@code "UNKNOWN"}.
     */
    @Query("""
            SELECT t.schemeId AS grp, t.status AS bucket, COUNT(t) AS cnt
            FROM TransactionEntity t
            WHERE t.createdAt >= :from AND t.createdAt < :to
            GROUP BY t.schemeId, t.status
            """)
    List<GroupStatusCount> countByCorridorAndStatus(
            @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Decline-reason tally over the DECLINED transactions in the window. Uses the real
     * {@code failure_reason} column (V004) as the reason; declined rows with a null
     * failure_reason collapse under a null key the service labels by their status
     * (e.g. {@code "CANCELLED"}). {@code declinedStatuses} is the terminal not-approved set.
     */
    @Query("""
            SELECT t.failureReason AS grp, t.status AS bucket, COUNT(t) AS cnt
            FROM TransactionEntity t
            WHERE t.createdAt >= :from AND t.createdAt < :to
              AND t.status IN :declinedStatuses
            GROUP BY t.failureReason, t.status
            """)
    List<GroupStatusCount> countDeclineReasons(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("declinedStatuses") List<String> declinedStatuses);

    /**
     * Earliest APPROVED {@code created_at} per partner ({@code partner_ref}) across ALL time —
     * the activation "first approved" signal the delivery overview folds against the partner's
     * onboarded timestamp. One row per partner that has at least one APPROVED transaction.
     */
    @Query("""
            SELECT t.partnerRef AS grp, MIN(t.createdAt) AS firstApproved
            FROM TransactionEntity t
            WHERE t.status = 'APPROVED'
            GROUP BY t.partnerRef
            """)
    List<FirstApprovedByPartner> findFirstApprovedByPartner();

    /** Projection: a status bucket and its count. */
    interface CountByBucket {
        String getBucket();
        long getCnt();
    }

    /** Projection: a group key (partner / corridor / reason), a status bucket, and the count. */
    interface GroupStatusCount {
        String getGrp();
        String getBucket();
        long getCnt();
    }

    /** Projection: a partner and the earliest instant it had an APPROVED transaction. */
    interface FirstApprovedByPartner {
        String getGrp();
        Instant getFirstApproved();
    }
}
