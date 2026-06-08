package com.gme.pay.ledger.domain.ledger;

import com.gme.pay.ledger.domain.model.Journal;

import java.util.List;
import java.util.Optional;

/**
 * Port: storage for posted {@link Journal} objects.
 * This service owns the {@code ledger} PostgreSQL database; no other service may write to it directly.
 */
public interface JournalStore {

    /** Persist a validated journal. Must be idempotent by journalId. */
    Journal save(Journal journal);

    /** Find a journal by its unique id. */
    Optional<Journal> findById(String journalId);

    /** All journals posted for a given transaction reference. */
    List<Journal> findByReference(String reference);
}
