package com.gme.pay.ledger.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable double-entry journal: a list of {@link LedgerEntry} lines that must balance
 * (sum of debits == sum of credits, per currency). Use {@link #post(List)} to create a validated journal.
 */
public final class Journal {

    private final String journalId;
    private final Instant postedAt;
    private final List<LedgerEntry> entries;

    private Journal(String journalId, Instant postedAt, List<LedgerEntry> entries) {
        this.journalId = journalId;
        this.postedAt = postedAt;
        this.entries = Collections.unmodifiableList(entries);
    }

    /**
     * Create and validate a journal. Throws {@link UnbalancedJournalException} if debits != credits
     * for any currency found in the entries.
     *
     * @param entries at least two entries (one DEBIT, one CREDIT)
     * @return a validated, immutable Journal
     */
    public static Journal post(List<LedgerEntry> entries) {
        Objects.requireNonNull(entries, "entries required");
        if (entries.size() < 2) {
            throw new IllegalArgumentException("A journal requires at least 2 entries");
        }
        assertBalanced(entries);
        return new Journal(UUID.randomUUID().toString(), Instant.now(), List.copyOf(entries));
    }

    /**
     * Re-hydrate a previously persisted journal from storage.
     *
     * <p>Used by the persistence layer (see {@code persistence.JpaJournalStore}) to reconstruct
     * a domain {@link Journal} with its original {@code journalId} and {@code postedAt}
     * preserved — {@link #post(List)} mints a new UUID + timestamp so it is not suitable for
     * read-back. Entries are still validated for balance.
     *
     * @param journalId stored journal id (must not be null)
     * @param postedAt  original posted timestamp (must not be null)
     * @param entries   the stored entries (validated for balance)
     */
    public static Journal rehydrate(String journalId, Instant postedAt, List<LedgerEntry> entries) {
        Objects.requireNonNull(journalId, "journalId required");
        Objects.requireNonNull(postedAt, "postedAt required");
        Objects.requireNonNull(entries, "entries required");
        if (entries.size() < 2) {
            throw new IllegalArgumentException("A journal requires at least 2 entries");
        }
        assertBalanced(entries);
        return new Journal(journalId, postedAt, List.copyOf(entries));
    }

    private static void assertBalanced(List<LedgerEntry> entries) {
        // Group by currency and check debit sum == credit sum for each currency
        var currencies = entries.stream().map(LedgerEntry::currency).distinct().toList();
        for (String ccy : currencies) {
            BigDecimal debits = entries.stream()
                    .filter(e -> e.currency().equals(ccy) && e.type() == EntryType.DEBIT)
                    .map(LedgerEntry::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credits = entries.stream()
                    .filter(e -> e.currency().equals(ccy) && e.type() == EntryType.CREDIT)
                    .map(LedgerEntry::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (debits.compareTo(credits) != 0) {
                throw new UnbalancedJournalException(ccy, debits, credits);
            }
        }
    }

    public String journalId() { return journalId; }
    public Instant postedAt() { return postedAt; }
    public List<LedgerEntry> entries() { return entries; }
}
