package com.gme.pay.txn.service;

import com.gme.pay.txn.domain.model.Transaction;

import java.util.Optional;

/**
 * Repository interface for {@link Transaction} aggregates.
 *
 * <p>Collaborators that own a different datastore (e.g. prefunding) are NEVER reached through
 * this interface – they are modelled as separate interfaces per the MSA rules.
 */
public interface TransactionRepository {

    /** Persist a new transaction or overwrite an existing one with the same {@code txnRef}. */
    Transaction save(Transaction txn);

    /** Find by the service-internal reference key. */
    Optional<Transaction> findByTxnRef(String txnRef);
}
