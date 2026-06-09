package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.domain.ledger.JournalStore;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JPA-backed {@link JournalStore} — the primary implementation in production.
 *
 * <p>When {@link #save(Journal)} is called this writes one {@link JournalEntity}
 * row plus one {@link LedgerEntryEntity} row per ledger line. The whole operation
 * runs in a single transaction (via {@link Transactional}).
 *
 * <p>Idempotency by {@code journalId}: a second {@code save} of an already-stored
 * journal returns the previously persisted journal unchanged (no duplicates).
 *
 * <p>The companion {@link InMemoryJournalStore} is kept as a non-primary fallback bean.
 */
@Component
@Primary
public class JpaJournalStore implements JournalStore {

    private final JournalEntityRepository journals;
    private final LedgerEntryEntityRepository entries;

    public JpaJournalStore(JournalEntityRepository journals, LedgerEntryEntityRepository entries) {
        this.journals = Objects.requireNonNull(journals, "journals repo required");
        this.entries = Objects.requireNonNull(entries, "entries repo required");
    }

    @Override
    @Transactional
    public Journal save(Journal journal) {
        Objects.requireNonNull(journal, "journal required");
        if (journals.existsById(journal.journalId())) {
            // Idempotent: do not overwrite an existing journal.
            return journal;
        }

        // Derive a stable reference from the first entry (every line in a journal carries the same reference).
        String reference = journal.entries().isEmpty() ? null : journal.entries().get(0).reference();

        journals.save(new JournalEntity(journal.journalId(), reference, journal.postedAt()));

        for (LedgerEntry e : journal.entries()) {
            entries.save(new LedgerEntryEntity(
                    journal.journalId(),
                    e.account(),
                    e.amount(),
                    e.currency(),
                    e.type().name(),
                    e.reference()
            ));
        }
        return journal;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Journal> findById(String journalId) {
        Optional<JournalEntity> head = journals.findById(journalId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        List<LedgerEntryEntity> lines = entries.findByJournalIdOrderByIdAsc(journalId);
        return Optional.of(rehydrate(head.get(), lines));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Journal> findByReference(String reference) {
        List<JournalEntity> heads = journals.findByReferenceOrderByPostedAtAsc(reference);
        List<Journal> out = new ArrayList<>(heads.size());
        for (JournalEntity h : heads) {
            List<LedgerEntryEntity> lines = entries.findByJournalIdOrderByIdAsc(h.getJournalId());
            out.add(rehydrate(h, lines));
        }
        return out;
    }

    /**
     * Rebuild a domain {@link Journal} from a persisted {@link JournalEntity} + its entries.
     *
     * <p>Uses {@link Journal#rehydrate(String, Instant, List)} so the stored {@code journalId}
     * and {@code postedAt} are preserved on read-back (unlike {@link Journal#post(List)}, which
     * mints a fresh id + timestamp on every call).
     */
    private static Journal rehydrate(JournalEntity head, List<LedgerEntryEntity> lines) {
        List<LedgerEntry> domainEntries = new ArrayList<>(lines.size());
        for (LedgerEntryEntity le : lines) {
            domainEntries.add(new LedgerEntry(
                    le.getAccount(),
                    le.getAmount(),
                    le.getCurrency(),
                    EntryType.valueOf(le.getEntryType()),
                    le.getReference()
            ));
        }
        return Journal.rehydrate(head.getJournalId(), head.getPostedAt(), domainEntries);
    }
}
