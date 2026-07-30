package com.gme.pay.ledger.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link CommissionSplitRecordEntity} (V005). Insert-only;
 * {@link #findByTxnRef} backs the idempotent replay (one split row per committed transaction).
 *
 * <p>The date-ranged queries below back the T2-4 commission-split ↔ journal reconciliation
 * ({@link RevenueJournalReconciliationService}).
 */
@Repository
public interface CommissionSplitRecordRepository
        extends JpaRepository<CommissionSplitRecordEntity, Long> {

    Optional<CommissionSplitRecordEntity> findByTxnRef(String txnRef);

    /** Number of commission-split records with a revenue date in the inclusive range. */
    long countByRevenueDateBetween(LocalDate start, LocalDate end);

    /** Splits in range whose net merchant fee is zero — nothing to journal, reported separately. */
    @Query("SELECT COUNT(c) FROM CommissionSplitRecordEntity c "
            + "WHERE c.revenueDate BETWEEN :start AND :end AND c.netMerchantFeeKrw = 0")
    long countZeroFeeInRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * The period's split totals as one row:
     * {@code Object[]{ net, gmeGross, scheme, partner, gmeNet }} (all Long, whole KRW).
     */
    @Query("""
            SELECT COALESCE(SUM(c.netMerchantFeeKrw), 0),
                   COALESCE(SUM(c.gmeGrossShareKrw), 0),
                   COALESCE(SUM(c.schemeShareKrw), 0),
                   COALESCE(SUM(c.partnerShareKrw), 0),
                   COALESCE(SUM(c.gmeNetShareKrw), 0)
            FROM CommissionSplitRecordEntity c
            WHERE c.revenueDate BETWEEN :start AND :end
            """)
    List<Object[]> sumSplitAmountsInRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * References of splits in range that carry a net fee but have NO fee-share journal — detected by the
     * absence of a CREDIT to {@code account} ({@code REVENUE_GME_FEE_SHARE}), the same rule the
     * posting-side idempotency guard uses, so detection and posting can never disagree.
     */
    @Query("""
            SELECT c.txnRef FROM CommissionSplitRecordEntity c
            WHERE c.revenueDate BETWEEN :start AND :end
              AND c.netMerchantFeeKrw > 0
              AND NOT EXISTS (
                  SELECT e.id FROM LedgerEntryEntity e
                  WHERE e.reference = c.txnRef
                    AND e.entryType = 'CREDIT'
                    AND e.account = :account)
            ORDER BY c.txnRef ASC
            """)
    List<String> findTxnRefsMissingFeeShareJournal(@Param("start") LocalDate start,
                                                   @Param("end") LocalDate end,
                                                   @Param("account") String account,
                                                   Pageable pageable);

    /**
     * References of splits in range that carry a partner commission carve but have NO carve journal
     * (T2-10) — detected by the absence of a CREDIT to {@code account} ({@code PAYABLE_PARTNER}), the same
     * rule {@code LedgerPostingService.postPartnerCommissionCarveJournal}'s idempotency guard uses, so
     * detection and posting can never disagree. A split whose {@code partner_share_pct} is zero carries no
     * carve and is correctly not an exception.
     */
    @Query("""
            SELECT c.txnRef FROM CommissionSplitRecordEntity c
            WHERE c.revenueDate BETWEEN :start AND :end
              AND c.partnerShareKrw > 0
              AND NOT EXISTS (
                  SELECT e.id FROM LedgerEntryEntity e
                  WHERE e.reference = c.txnRef
                    AND e.entryType = 'CREDIT'
                    AND e.account = :account)
            ORDER BY c.txnRef ASC
            """)
    List<String> findTxnRefsMissingPartnerCarveJournal(@Param("start") LocalDate start,
                                                      @Param("end") LocalDate end,
                                                      @Param("account") String account,
                                                      Pageable pageable);

    /** Exact count behind {@link #findTxnRefsMissingPartnerCarveJournal} (the list is capped; this is not). */
    @Query("""
            SELECT COUNT(c) FROM CommissionSplitRecordEntity c
            WHERE c.revenueDate BETWEEN :start AND :end
              AND c.partnerShareKrw > 0
              AND NOT EXISTS (
                  SELECT e.id FROM LedgerEntryEntity e
                  WHERE e.reference = c.txnRef
                    AND e.entryType = 'CREDIT'
                    AND e.account = :account)
            """)
    long countSplitsMissingPartnerCarveJournal(@Param("start") LocalDate start,
                                              @Param("end") LocalDate end,
                                              @Param("account") String account);

    /**
     * Total {@code partner_share_krw} of splits in range that have NO carve journal — i.e. the carve money
     * genuinely still off the double-entry books (T2-10). Summing the exceptions rather than subtracting
     * two totals means a partial back-fill reports exactly what remains, and a reversal's mirroring DEBIT
     * of {@code PAYABLE_PARTNER} cannot make a booked carve look unbooked.
     */
    @Query("""
            SELECT COALESCE(SUM(c.partnerShareKrw), 0) FROM CommissionSplitRecordEntity c
            WHERE c.revenueDate BETWEEN :start AND :end
              AND c.partnerShareKrw > 0
              AND NOT EXISTS (
                  SELECT e.id FROM LedgerEntryEntity e
                  WHERE e.reference = c.txnRef
                    AND e.entryType = 'CREDIT'
                    AND e.account = :account)
            """)
    long sumUnjournalledPartnerCarveInRange(@Param("start") LocalDate start,
                                            @Param("end") LocalDate end,
                                            @Param("account") String account);

    /**
     * Total CREDITs posted to {@code account} in {@code currency} for the references of splits whose
     * revenue date falls in range. Scoped by reference set (not journal post date) so business-date vs
     * post-date skew cannot look like a variance; CREDITs only, since reversals debit these accounts.
     */
    @Query("""
            SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntryEntity e
            WHERE e.account = :account
              AND e.currency = :currency
              AND e.entryType = 'CREDIT'
              AND e.reference IN (
                  SELECT c.txnRef FROM CommissionSplitRecordEntity c
                  WHERE c.revenueDate BETWEEN :start AND :end)
            """)
    BigDecimal sumJournalledCreditForSplitsInRange(@Param("account") String account,
                                                   @Param("currency") String currency,
                                                   @Param("start") LocalDate start,
                                                   @Param("end") LocalDate end);
}
