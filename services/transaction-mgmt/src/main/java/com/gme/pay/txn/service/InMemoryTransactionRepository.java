package com.gme.pay.txn.service;

import com.gme.pay.txn.domain.model.Transaction;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase-1 in-memory implementation of {@link TransactionRepository}.
 *
 * <p>Replaced by a Spring Data JPA / PostgreSQL adapter in the persistence wave
 * without changing any service or controller code.
 */
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<String, Transaction> store = new ConcurrentHashMap<>();

    @Override
    public Transaction save(Transaction txn) {
        store.put(txn.txnRef(), txn);
        return txn;
    }

    @Override
    public Optional<Transaction> findByTxnRef(String txnRef) {
        return Optional.ofNullable(store.get(txnRef));
    }
}
