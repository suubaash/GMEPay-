package com.gme.pay.txn.service;

import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for {@link Transaction} aggregates.
 *
 * <p>Collaborators that own a different datastore (e.g. prefunding) are NEVER reached through
 * this interface – they are modelled as separate interfaces per the MSA rules.
 *
 * <p>V003 adds paged query for GET /v1/transactions.
 */
public interface TransactionRepository {

    /** Persist a new transaction or overwrite an existing one with the same {@code txnRef}. */
    Transaction save(Transaction txn);

    /** Find by the service-internal reference key. */
    Optional<Transaction> findByTxnRef(String txnRef);

    /**
     * Paged query by optional filters.
     *
     * @param from      lower bound (inclusive) on createdAt date (null = no lower bound)
     * @param to        upper bound (inclusive) on createdAt date (null = no upper bound)
     * @param status    filter by status (null = all)
     * @param partnerId filter by partnerId (null = all)
     * @param schemeId  filter by scheme_id (the QR scheme identity, e.g. ZEROPAY/NEPAL; null = all)
     * @param pageable  pagination spec
     */
    Page<Transaction> findByFilters(LocalDate from, LocalDate to,
                                    TransactionStatus status, Long partnerId,
                                    String txnRef, String schemeTxnRef, String merchantId,
                                    String userRef, String reference, String schemeId,
                                    Pageable pageable);

    /**
     * Ops stuck-transaction sweep. Returns transactions whose status is in {@code sweepStatuses}
     * and that have not been updated since {@code stuckBefore} (aged beyond the threshold).
     *
     * @param stuckBefore   upper-exclusive bound on updatedAt (i.e. now minus threshold)
     * @param sweepStatuses status names considered stuck-eligible (e.g. UNCERTAIN, PENDING_DEBIT)
     */
    List<Transaction> findStuck(Instant stuckBefore, List<String> sweepStatuses);

    /**
     * Returns transactions in a non-terminal sweepable state whose {@code createdAt}
     * is strictly before {@code expiryBefore}.
     *
     * <p>Only states that can legally transition to FAILED are returned
     * (currently CREATED and PENDING_DEBIT).  Terminal states are never swept.
     *
     * @param expiryBefore  cutoff instant (exclusive); rows older than this are candidates
     */
    List<Transaction> findExpiredNonTerminal(Instant expiryBefore);

    /**
     * V007: committed-FX projection feed. Returns committed transactions whose {@code committedAt}
     * falls in {@code [from, to)} (instant window for the requested date range), optionally for one
     * partner. Used by GET /v1/transactions/fx-committed.
     */
    List<Transaction> findCommittedFx(LocalDate from, LocalDate to, Long partnerId);

    /**
     * V007: refund query. Returns transactions refunded on the given calendar day
     * ({@code refundedAt} in that day's instant window). Used by GET /v1/transactions/refunded.
     */
    List<Transaction> findRefundedOn(LocalDate refundedOn);

    // -------------------------------------------------------------------------
    // Delivery-dashboard aggregates (GET /v1/transactions/stats). Grouped, read-only
    // counts over the instant window {@code [from, to)} — never load all rows.
    // -------------------------------------------------------------------------

    // Additive default methods (return empty) so existing test-fake implementations of this port
    // keep compiling without change; the production JPA adapter overrides all five.

    /** Per-status counts in the window (one entry per distinct status present). */
    default List<StatusCount> countByStatus(Instant from, Instant to) {
        return List.of();
    }

    /** Per-(partner_ref, status) counts in the window. */
    default List<GroupCount> countByPartnerAndStatus(Instant from, Instant to) {
        return List.of();
    }

    /** Per-(scheme_id, status) counts in the window; a null scheme rides as {@code null} grp. */
    default List<GroupCount> countByCorridorAndStatus(Instant from, Instant to) {
        return List.of();
    }

    /**
     * Decline-reason tally over the declined transactions in the window. {@code grp} is the
     * {@code failure_reason} (nullable); {@code bucket} is the status so the caller can label a
     * null reason by its status.
     */
    default List<GroupCount> countDeclineReasons(Instant from, Instant to, List<String> declinedStatuses) {
        return List.of();
    }

    /** Earliest APPROVED {@code created_at} per partner_ref across all time (activation signal). */
    default List<FirstApproved> findFirstApprovedByPartner() {
        return List.of();
    }

    /** A status name and its count. */
    record StatusCount(String status, long count) {}

    /** A group key (partner / corridor / reason), a status bucket, and the count. */
    record GroupCount(String grp, String status, long count) {}

    /** A partner_ref and the earliest instant it had an APPROVED transaction. */
    record FirstApproved(String partner, Instant firstApprovedAt) {}
}
