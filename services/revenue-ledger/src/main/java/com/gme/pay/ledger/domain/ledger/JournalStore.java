package com.gme.pay.ledger.domain.ledger;

import com.gme.pay.ledger.domain.model.Journal;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /**
     * Signed net total posted to the {@code REVENUE_ROUNDING} account in {@code currency} over the
     * inclusive date range {@code [start, end]} (by journal posted date). CREDIT entries add (rounding
     * GAIN), DEBIT entries subtract (rounding LOSS), so the result is the net rounding gain/loss for
     * the period — and reconciles to the sum of the posted residuals. Returns {@code 0} (never null)
     * when no rounding journals fall in range.
     */
    BigDecimal sumRoundingByDateRange(LocalDate start, LocalDate end, String currency);
}
