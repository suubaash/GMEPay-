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
     * @param pageable  pagination spec
     */
    Page<Transaction> findByFilters(LocalDate from, LocalDate to,
                                    TransactionStatus status, Long partnerId,
                                    Pageable pageable);

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
}
