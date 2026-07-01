package com.gme.sim.nepalqr.model;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory store.
 *   - records   : every inbound request/response (append order preserved)
 *   - txns      : transactions created by /pay/, keyed by unique reference
 *
 * This is the whole point of the mock: whenever GMEPay+ calls to create a txn,
 * the request + response are stored and can be inspected.
 */
@Component
public class NepalQrStore {

    private final CopyOnWriteArrayList<SimRecord> records = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, TxnRecord> txnsByReference = new ConcurrentHashMap<>();

    // --- Records ---

    public void save(SimRecord r) {
        records.add(r);
    }

    /** Newest-first, optionally filtered by reference. */
    public List<SimRecord> records(String referenceFilter) {
        List<SimRecord> out = new ArrayList<>(records);
        java.util.Collections.reverse(out);
        if (referenceFilter != null && !referenceFilter.isBlank()) {
            out.removeIf(r -> !referenceFilter.equals(r.reference));
        }
        return out;
    }

    public Optional<SimRecord> findRecord(String id) {
        return records.stream().filter(r -> id.equals(r.id)).findFirst();
    }

    // --- Transactions (dedup on reference) ---

    /** @return true if the reference has already been used to create a txn. */
    public boolean referenceExists(String reference) {
        return txnsByReference.containsKey(reference);
    }

    public void saveTxn(TxnRecord txn) {
        txnsByReference.put(txn.reference, txn);
    }

    public Optional<TxnRecord> findTxn(String reference) {
        return Optional.ofNullable(txnsByReference.get(reference));
    }
}
