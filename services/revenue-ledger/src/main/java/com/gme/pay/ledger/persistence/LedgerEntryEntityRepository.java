package com.gme.pay.ledger.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link LedgerEntryEntity}.
 *
 * <p>Lines are looked up by {@code journalId} (to reconstruct a journal) or by
 * {@code reference} (to find all entries posted against one transaction reference,
 * which is the primary report query).
 */
public interface LedgerEntryEntityRepository extends JpaRepository<LedgerEntryEntity, Long> {

    /** All entries for one journal, in insertion order. */
    List<LedgerEntryEntity> findByJournalIdOrderByIdAsc(String journalId);

    /** All entries posted for a transaction reference across any journal. */
    List<LedgerEntryEntity> findByReferenceOrderByIdAsc(String reference);

    /** All entries posted to a specific account (used by aggregation queries). */
    List<LedgerEntryEntity> findByAccountOrderByIdAsc(String account);

    /**
     * Net signed total for {@code account} in {@code currency} over a journal {@code posted_at}
     * window {@code [start, end)} (end-exclusive instant). CREDIT lines add, DEBIT lines subtract —
     * so the result is the signed rounding gain (positive) / loss (negative) per
     * {@code docs/MONEY_CONVENTION.md}. COALESCE 0 when no lines match (never null).
     *
     * <p>Joined to {@code journals} by {@code journalId} for the time filter, since the date lives
     * on the journal head, not the entry line.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
            FROM LedgerEntryEntity e, JournalEntity j
            WHERE e.journalId = j.journalId
              AND e.account = :account
              AND e.currency = :currency
              AND j.postedAt >= :start
              AND j.postedAt < :end
            """)
    BigDecimal sumSignedByAccountAndCurrencyAndPostedAtBetween(@Param("account") String account,
                                                               @Param("currency") String currency,
                                                               @Param("start") Instant start,
                                                               @Param("end") Instant end);
}
