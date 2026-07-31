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

    // ---- AML monitoring window (gap T5-3) --------------------------------------------------
    //
    // The four queries above answer "how much cap has this partner consumed in the ONE period this
    // transaction falls into" — a point question asked under the row lock on the hot authorize path.
    // AML monitoring asks a different question: "what did this partner's volume and velocity look
    // like, day by day, over a window", which is what an investigator or a rule needs and what no
    // query here could produce. Adding it as a windowed GROUP BY rather than looping sumDaily() per
    // day matters: a 90-day look-back would otherwise be 90 round trips, and the per-day rows are the
    // evidence — the shape a reviewer reads — not an intermediate the caller re-aggregates.
    //
    // The existing queries are deliberately untouched: they are on the money path and their race
    // properties are proven by their own tests.

    /**
     * Per-KST-day usage for one partner over an INCLUSIVE {@code [fromDailyKey, toDailyKey]} range,
     * oldest day first. Range comparison is a plain lexicographic string compare, which is exactly a
     * date compare because {@code daily_key} is zero-padded {@code yyyy-MM-dd} — the same property
     * that lets the {@code idx_cum_usage_daily} index serve the range.
     *
     * <p>Three numbers per day, because they answer three different questions and collapsing them
     * would hide the interesting case:
     * <ul>
     *   <li>{@code netUsd} — SUM of the SIGNED amount, so a {@code CUM_REVERSE} nets its charge back
     *       out of the day it was CHARGED in (the reverse carries the charge's original period keys).
     *       This is the volume the partner actually consumed.</li>
     *   <li>{@code netTxnCount} — {@code CUM_CHARGE} +1 / {@code CUM_REVERSE} -1, identical to
     *       {@link #netDailyCount}. This is the velocity the cap gate uses.</li>
     *   <li>{@code chargeCount} — raw charges, reverses NOT subtracted. A day of 200 charges that
     *       were all reversed nets to zero volume and zero velocity, and that is correct for a cap;
     *       it is also a pattern worth someone's attention, and a monitoring surface that could only
     *       report the netted numbers would render it as an idle day.</li>
     * </ul>
     *
     * <p>A day with no ledger rows produces NO row — absence of activity is absence of a row, not a
     * zero row, and the caller must not read a missing day as anything else.
     *
     * <p>This is an EVIDENCE query. It applies no threshold and encodes no notion of suspicion.
     */
    @Query("SELECT e.dailyKey AS dailyKey, "
            + "COALESCE(SUM(e.amountUsd), 0) AS netUsd, "
            + "COALESCE(SUM(CASE WHEN e.entryType = 'CUM_CHARGE' THEN 1 "
            + "WHEN e.entryType = 'CUM_REVERSE' THEN -1 ELSE 0 END), 0) AS netTxnCount, "
            + "COALESCE(SUM(CASE WHEN e.entryType = 'CUM_CHARGE' THEN 1 ELSE 0 END), 0) AS chargeCount "
            + "FROM CumulativeUsageLedgerEntity e "
            + "WHERE e.partnerId = :partnerId "
            + "AND e.dailyKey >= :fromKey AND e.dailyKey <= :toKey "
            + "GROUP BY e.dailyKey ORDER BY e.dailyKey ASC")
    List<DailyUsageRow> dailyUsageWindow(@Param("partnerId") String partnerId,
                                         @Param("fromKey") String fromDailyKey,
                                         @Param("toKey") String toDailyKey);

    /**
     * Projection of one KST day of {@link #dailyUsageWindow}. An interface projection rather than a
     * constructor expression so the aggregate types Hibernate picks for the {@code SUM(CASE …)}
     * expressions are converted rather than having to be guessed exactly in a {@code new} clause.
     */
    interface DailyUsageRow {

        /** The KST day, {@code yyyy-MM-dd}. */
        String getDailyKey();

        /** SUM of the signed {@code amount_usd} — charges minus reverses. */
        BigDecimal getNetUsd();

        /** Charges minus reverses, as a count. Matches the velocity cap's arithmetic. */
        long getNetTxnCount();

        /** Charges only, reverses NOT subtracted. */
        long getChargeCount();
    }
}
