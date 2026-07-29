package com.gme.pay.ledger.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link RevenueRecordEntity}. Backs
 * {@link JpaRevenueRecordStore}. Sum queries use {@code COALESCE(...,0)} so an empty range
 * returns zero rather than null. Date predicates use inclusive BETWEEN.
 */
public interface RevenueRecordJpaRepository extends JpaRepository<RevenueRecordEntity, Long> {

    Optional<RevenueRecordEntity> findByTxnRef(String txnRef);

    @Query("SELECT COALESCE(SUM(r.fxMarginUsd), 0) FROM RevenueRecordEntity r "
            + "WHERE r.partnerId = :partnerId AND r.revenueDate BETWEEN :start AND :end")
    BigDecimal sumFxMarginUsdByPartner(@Param("partnerId") long partnerId,
                                       @Param("start") LocalDate start,
                                       @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(r.serviceChargeAmount), 0) FROM RevenueRecordEntity r "
            + "WHERE r.partnerId = :partnerId AND r.revenueDate BETWEEN :start AND :end")
    BigDecimal sumServiceChargeByPartner(@Param("partnerId") long partnerId,
                                         @Param("start") LocalDate start,
                                         @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(r.serviceChargeAmount), 0) FROM RevenueRecordEntity r "
            + "WHERE r.schemeId = :schemeId AND r.revenueDate BETWEEN :start AND :end")
    BigDecimal sumServiceChargeByScheme(@Param("schemeId") long schemeId,
                                        @Param("start") LocalDate start,
                                        @Param("end") LocalDate end);

    long countByPartnerIdAndRevenueDateBetween(long partnerId, LocalDate start, LocalDate end);

    Optional<RevenueRecordEntity> findFirstByPartnerIdAndRevenueDateBetweenOrderByRevenueDateDesc(
            long partnerId, LocalDate start, LocalDate end);

    List<RevenueRecordEntity> findByRevenueDateBetweenOrderByRevenueDateAsc(
            LocalDate start, LocalDate end, Pageable pageable);

    // ---------------------------------------------------------------------------------------------
    // T2-4 revenue-record ↔ journal reconciliation (RevenueJournalReconciliationService).
    // These are period-wide (not partner-scoped) because a trial balance / day-close is service-wide.
    // ---------------------------------------------------------------------------------------------

    /** Number of revenue records with a revenue date in the inclusive range. */
    long countByRevenueDateBetween(LocalDate start, LocalDate end);

    /**
     * Number of records in range carrying NO revenue at all (both components zero). These have nothing
     * to journal, so they are reported separately rather than counted as recorded-but-not-journalled.
     */
    @Query("SELECT COUNT(r) FROM RevenueRecordEntity r "
            + "WHERE r.revenueDate BETWEEN :start AND :end "
            + "AND r.fxMarginUsd = 0 AND r.serviceChargeAmount = 0")
    long countZeroRevenueInRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** Total FX margin (USD) recorded in range — the figure the journal's FX-margin credits must match. */
    @Query("SELECT COALESCE(SUM(r.fxMarginUsd), 0) FROM RevenueRecordEntity r "
            + "WHERE r.revenueDate BETWEEN :start AND :end")
    BigDecimal sumFxMarginUsdInRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * Service charge recorded in range, grouped by its currency. Rows are
     * {@code Object[]{ currency:String, amount:BigDecimal }} — service charge is NOT USD-denominated
     * (KRW on the domestic path), so the tie-out has to be per currency.
     */
    @Query("SELECT r.serviceChargeCcy, COALESCE(SUM(r.serviceChargeAmount), 0) FROM RevenueRecordEntity r "
            + "WHERE r.revenueDate BETWEEN :start AND :end "
            + "GROUP BY r.serviceChargeCcy ORDER BY r.serviceChargeCcy ASC")
    List<Object[]> sumServiceChargeByCurrencyInRange(@Param("start") LocalDate start,
                                                     @Param("end") LocalDate end);

    /**
     * Business references of records in range that carry revenue but have NO capture journal — the
     * recorded-but-not-journalled exceptions the finance team must see.
     *
     * <p>"Journalled" = a CREDIT line exists against the reference on one of {@code incomeAccounts}
     * ({@code REVENUE_FX_MARGIN} / {@code REVENUE_SERVICE_CHARGE}); only an ORIGINAL capture credits
     * those (a reversal mirrors the sides), which is the same rule the posting-side idempotency guard
     * uses, so the two can never disagree.
     */
    @Query("""
            SELECT r.txnRef FROM RevenueRecordEntity r
            WHERE r.revenueDate BETWEEN :start AND :end
              AND (r.fxMarginUsd > 0 OR r.serviceChargeAmount > 0)
              AND NOT EXISTS (
                  SELECT e.id FROM LedgerEntryEntity e
                  WHERE e.reference = r.txnRef
                    AND e.entryType = 'CREDIT'
                    AND e.account IN :incomeAccounts)
            ORDER BY r.txnRef ASC
            """)
    List<String> findTxnRefsMissingCaptureJournal(@Param("start") LocalDate start,
                                                  @Param("end") LocalDate end,
                                                  @Param("incomeAccounts") Collection<String> incomeAccounts,
                                                  Pageable pageable);

    /**
     * Total CREDITs posted to {@code account} in {@code currency} for the references of records whose
     * revenue date falls in range.
     *
     * <p>Scoped by REFERENCE SET, not by journal post date, so the tie-out is immune to the skew between
     * a record's business {@code revenue_date} and the wall-clock {@code journals.posted_at} (a capture
     * journalled just after midnight would otherwise look like a variance).
     *
     * <p>CREDITs only, deliberately: reversals DEBIT the income accounts, while a revenue record is never
     * deleted or reversed, so gross credits are the correct counterpart. Reversal activity stays visible
     * in the trial balance as debits to the income accounts.
     */
    @Query("""
            SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntryEntity e
            WHERE e.account = :account
              AND e.currency = :currency
              AND e.entryType = 'CREDIT'
              AND e.reference IN (
                  SELECT r.txnRef FROM RevenueRecordEntity r
                  WHERE r.revenueDate BETWEEN :start AND :end)
            """)
    BigDecimal sumJournalledCreditForRecordsInRange(@Param("account") String account,
                                                    @Param("currency") String currency,
                                                    @Param("start") LocalDate start,
                                                    @Param("end") LocalDate end);
}
