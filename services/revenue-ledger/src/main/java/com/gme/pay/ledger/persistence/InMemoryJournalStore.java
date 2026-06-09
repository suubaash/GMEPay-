package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.domain.ledger.JournalStore;
import com.gme.pay.ledger.domain.model.Journal;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory fallback {@link JournalStore} — kept as a no-DB fallback for local
 * exploratory runs and as the reference implementation that the test suite
 * stubs against.
 *
 * <p>NOT marked {@code @Primary}: the JPA-backed {@link JpaJournalStore} wins by
 * default. This bean is still exposed so callers/tests that want a transient
 * in-memory store can inject it explicitly via {@code @Qualifier("inMemoryJournalStore")}.
 */
@Component("inMemoryJournalStore")
public class InMemoryJournalStore implements JournalStore {

    private final ConcurrentHashMap<String, Journal> byId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Journal> insertionOrder = new CopyOnWriteArrayList<>();

    @Override
    public Journal save(Journal journal) {
        Journal existing = byId.putIfAbsent(journal.journalId(), journal);
        if (existing != null) {
            // Idempotent by journalId: a second save returns the originally stored journal.
            return existing;
        }
        insertionOrder.add(journal);
        return journal;
    }

    @Override
    public Optional<Journal> findById(String journalId) {
        return Optional.ofNullable(byId.get(journalId));
    }

    @Override
    public List<Journal> findByReference(String reference) {
        return insertionOrder.stream()
                .filter(j -> j.entries().stream().anyMatch(e -> reference.equals(e.reference())))
                .toList();
    }
}
