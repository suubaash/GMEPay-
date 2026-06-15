package com.gme.pay.txn.service;

import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
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
}
