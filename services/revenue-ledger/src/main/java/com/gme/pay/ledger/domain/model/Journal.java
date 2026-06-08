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
