package com.gme.pay.prefunding.persistence;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for the append-only {@link CumulativeUsageLedgerEntity}. The {@code sum*} queries return the
 * NET usage (charges minus reverses, since reverses are stored signed-negative) for a partner in one period
 * bucket — read inside the per-partner row lock so the cap comparison is race-free.
 */
@Repository
public interface CumulativeUsageLedgerRepository extends JpaRepository<CumulativeUsageLedgerEntity, Long> {

    /** All rows for one (partner, txnRef) — drives charge/reverse idempotency + locating a charge to reverse. */
    List<CumulativeUsageLedgerEntity> findByPartnerIdAndTxnRef(String partnerId, String txnRef);

    @Query("SELECT COALESCE(SUM(e.amountUsd), 0) FROM CumulativeUsageLedgerEntity e "
            + "WHERE e.partnerId = :partnerId AND e.dailyKey = :key")
    BigDecimal sumDaily(@Param("partnerId") String partnerId, @Param("key") String dailyKey);

    @Query("SELECT COALESCE(SUM(e.amountUsd), 0) FROM CumulativeUsageLedgerEntity e "
            + "WHERE e.partnerId = :partnerId AND e.monthlyKey = :key")
    BigDecimal sumMonthly(@Param("partnerId") String partnerId, @Param("key") String monthlyKey);

    @Query("SELECT COALESCE(SUM(e.amountUsd), 0) FROM CumulativeUsageLedgerEntity e "
            + "WHERE e.partnerId = :partnerId AND e.annualKey = :key")
    BigDecimal sumAnnual(@Param("partnerId") String partnerId, @Param("key") String annualKey);

    /**
     * Net transaction COUNT for a partner on one KST day = charges minus reverses (a reversed txn no
     * longer counts toward velocity). Backs the daily transaction-count cap (V034 / WBS 13.8).
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType = 'CUM_CHARGE' THEN 1 "
            + "WHEN e.entryType = 'CUM_REVERSE' THEN -1 ELSE 0 END), 0) "
            + "FROM CumulativeUsageLedgerEntity e WHERE e.partnerId = :partnerId AND e.dailyKey = :key")
    long netDailyCount(@Param("partnerId") String partnerId, @Param("key") String dailyKey);
}
