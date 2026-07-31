package com.gme.pay.ledger.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
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
     * Batch-load the lines for a page of journals in ONE query (avoids the N+1 that a
     * per-journal {@link #findByJournalIdOrderByIdAsc(String)} loop would cause). Ordered by
     * {@code journalId} then {@code id} so a caller can group consecutively by journal and keep
     * each journal's lines in insertion order. Used by {@code GET /v1/journals}.
     */
    List<LedgerEntryEntity> findByJournalIdInOrderByJournalIdAscIdAsc(Collection<String> journalIds);

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

    /**
     * <b>Trial balance</b> aggregate (T2-4): per {@code (account, currency)}, the total DEBITs, total
     * CREDITs and line count for journals posted in {@code [start, end)} (end-exclusive instant).
     *
     * <p>Each row is {@code Object[]{ account:String, currency:String, debit:BigDecimal,
     * credit:BigDecimal, lineCount:Long }}, ordered by currency then account so the report is stable.
     * Joined to {@code journals} for the time filter because the post date lives on the journal head.
     *
     * <p>The caller ({@link TrialBalanceService}) sums the debit and credit columns per currency; a
     * non-zero difference means the book does not balance and is reported as an imbalance rather than
     * hidden. Amounts are grouped, never rounded — the raw {@code NUMERIC(20,8)} sums come back.
     */
    @Query("""
            SELECT e.account, e.currency,
                   COALESCE(SUM(CASE WHEN e.entryType = 'DEBIT'  THEN e.amount ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE 0 END), 0),
                   COUNT(e)
            FROM LedgerEntryEntity e, JournalEntity j
            WHERE e.journalId = j.journalId
              AND j.postedAt >= :start
              AND j.postedAt < :end
            GROUP BY e.account, e.currency
            ORDER BY e.currency ASC, e.account ASC
            """)
    List<Object[]> trialBalanceRows(@Param("start") Instant start, @Param("end") Instant end);
}
