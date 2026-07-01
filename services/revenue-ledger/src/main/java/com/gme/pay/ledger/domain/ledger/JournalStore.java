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
     * Find the existing rounding-residual journal for {@code reference}, if one was already posted.
     *
     * <p>A rounding-residual journal is identified by carrying an entry against the
     * {@code REVENUE_ROUNDING} account. This lookup is the idempotency guard for
     * {@link LedgerPostingService#postRoundingResidual}: settlement-reconciliation (per settlement
     * batch id) and payment-executor (per TXN ref) may both retry a post with the same reference, and
     * a repeat must NOT create a second rounding journal. Because only rounding journals touch
     * {@code REVENUE_ROUNDING}, this coexists with revenue-capture / fee-share / reversal journals that
     * carry the same {@code reference} on OTHER accounts without colliding.
     *
     * @return the existing rounding journal for this reference, or {@link Optional#empty()} if none
     */
    Optional<Journal> findRoundingResidualByReference(String reference);

    /**
     * Signed net total posted to the {@code REVENUE_ROUNDING} account in {@code currency} over the
     * inclusive date range {@code [start, end]} (by journal posted date). CREDIT entries add (rounding
     * GAIN), DEBIT entries subtract (rounding LOSS), so the result is the net rounding gain/loss for
     * the period — and reconciles to the sum of the posted residuals. Returns {@code 0} (never null)
     * when no rounding journals fall in range.
     */
    BigDecimal sumRoundingByDateRange(LocalDate start, LocalDate end, String currency);
}
