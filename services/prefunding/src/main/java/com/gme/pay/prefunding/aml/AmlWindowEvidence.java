package com.gme.pay.prefunding.aml;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What one partner's {@code cumulative_usage_ledger} actually says over a window of KST days — gap
 * T5-3.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Prefunding already held the only complete, append-only record of per-partner transaction volume
 * and velocity in the platform ({@code cumulative_usage_ledger}, V006). It was queryable in exactly
 * one shape: "how much of THIS period's cap has THIS transaction's partner consumed", asked under a
 * row lock on the authorize path. That is a gate, and a good one, but nobody could ask the ledger the
 * question an AML reviewer or a regulator asks — <i>show me what this partner did, day by day, over
 * this period</i> — without a direct database session. This value object is that answer.
 *
 * <h2>This is EVIDENCE, not a control</h2>
 *
 * <p>Nothing in here is a judgement. Every number is a reproducible aggregate of append-only rows,
 * and the caps carried alongside are reported as CONTEXT — they are what config-registry configured
 * for this partner, which is the frame a reader needs to interpret the volume, not a rule this class
 * evaluates. An instance of this record does not decide anything, does not flag anything, and does
 * not constitute an AML control. {@link AmlMonitoringEvaluator} applies operator-configured rules to
 * it, and those rules ship empty.
 *
 * <h2>Absent days</h2>
 *
 * <p>{@link #days()} contains only the KST days that HAVE ledger rows, oldest first. A day with no
 * activity is absent rather than present-and-zero. That is a deliberate choice: the ledger records
 * what happened, and manufacturing zero rows for the gaps would put rows into the evidence that no
 * transaction ever produced. It also does not change any metric — a missing day contributes zero to
 * every sum and cannot be the maximum of a non-negative-activity window.
 *
 * @param partnerId          the partner code the window belongs to
 * @param fromDailyKey       inclusive lower bound, {@code yyyy-MM-dd} KST
 * @param toDailyKey         inclusive upper bound, {@code yyyy-MM-dd} KST
 * @param days               per-day rows, ascending by day; days without activity are absent
 * @param windowNetUsd       SUM of the signed amounts over the whole window (never null)
 * @param windowNetTxnCount  charges minus reverses over the whole window
 * @param caps               the partner's CONFIGURED caps, as context; each null means unconstrained
 */
public record AmlWindowEvidence(String partnerId,
                                String fromDailyKey,
                                String toDailyKey,
                                List<DayUsage> days,
                                BigDecimal windowNetUsd,
                                long windowNetTxnCount,
                                ConfiguredCaps caps) {

    /**
     * One KST day of activity.
     *
     * @param dailyKey    the KST day, {@code yyyy-MM-dd}
     * @param netUsd      signed sum for the day — a reverse nets its charge back out of this
     * @param netTxnCount charges minus reverses (the velocity cap's arithmetic)
     * @param chargeCount charges only, reverses NOT subtracted; a day whose charges were all
     *                    reversed nets to zero here but still shows how many were attempted
     */
    public record DayUsage(String dailyKey, BigDecimal netUsd, long netTxnCount, long chargeCount) { }

    /**
     * The per-partner AML caps as CONFIGURED on {@code partner_balance} (pushed from config-registry,
     * V007/V034). Reported as context only — this record evaluates nothing against them.
     *
     * <p>A {@code null} field means <b>unconstrained</b> for that period, which is a materially
     * different statement from "the cap is zero" and is why every field is nullable rather than
     * defaulted. A reviewer looking at a large window has to be able to see that no cap was in force.
     */
    public record ConfiguredCaps(BigDecimal dailyCapUsd, BigDecimal monthlyCapUsd,
                                 BigDecimal annualCapUsd, Integer dailyTxnCountCap) {

        /** All-unconstrained. */
        public static ConfiguredCaps none() {
            return new ConfiguredCaps(null, null, null, null);
        }
    }

    /** Build an instance from the per-day rows, deriving the window totals from them. */
    public static AmlWindowEvidence of(String partnerId, String fromDailyKey, String toDailyKey,
                                       List<DayUsage> days, ConfiguredCaps caps) {
        List<DayUsage> rows = days == null ? List.of() : List.copyOf(days);
        BigDecimal netUsd = BigDecimal.ZERO;
        long netCount = 0L;
        for (DayUsage d : rows) {
            netUsd = netUsd.add(d.netUsd() == null ? BigDecimal.ZERO : d.netUsd());
            netCount += d.netTxnCount();
        }
        return new AmlWindowEvidence(partnerId, fromDailyKey, toDailyKey, rows, netUsd, netCount,
                caps == null ? ConfiguredCaps.none() : caps);
    }

    /** Inclusive span of the window in calendar days ({@code from == to} is 1 day). */
    public int spanDays() {
        return (int) (LocalDate.parse(toDailyKey).toEpochDay()
                - LocalDate.parse(fromDailyKey).toEpochDay()) + 1;
    }

    /**
     * The TRAILING sub-window of {@code windowDays} calendar days ending at {@link #toDailyKey}, as
     * a fresh evidence object with recomputed totals and the same caps.
     *
     * <p>Used for a rule that declares its own {@code windowDays}: the rule is measured over the most
     * recent N days of whatever window was read, never over a re-query, so one read answers every
     * rule and every rule reports the exact bounds it used.
     *
     * @throws IllegalArgumentException if {@code windowDays} is not positive, or is LONGER than this
     *         window. Clamping instead would evaluate a 30-day rule against 7 days of data and report
     *         it as a pass — under-reporting dressed up as a result.
     */
    public AmlWindowEvidence trailingWindow(int windowDays) {
        if (windowDays <= 0) {
            throw new IllegalArgumentException("windowDays must be positive, got " + windowDays);
        }
        int span = spanDays();
        if (windowDays > span) {
            throw new IllegalArgumentException("windowDays " + windowDays
                    + " exceeds the evidence window's span of " + span + " day(s) ["
                    + fromDailyKey + ".." + toDailyKey + "]");
        }
        if (windowDays == span) {
            return this;
        }
        String subFrom = LocalDate.parse(toDailyKey).minusDays(windowDays - 1L).toString();
        List<DayUsage> subset = new ArrayList<>();
        for (DayUsage d : days) {
            if (d.dailyKey().compareTo(subFrom) >= 0) {
                subset.add(d);
            }
        }
        return of(partnerId, subFrom, toDailyKey, subset, caps);
    }

    /**
     * The observed value of {@code metric} over this window, as a {@link BigDecimal} so a count and
     * an amount compare against a configured threshold by the same exact arithmetic (no doubles, no
     * rounding). Counts are exact integers in decimal form.
     */
    public BigDecimal observe(AmlMetric metric) {
        return switch (metric) {
            case WINDOW_NET_USD -> windowNetUsd;
            case WINDOW_NET_TXN_COUNT -> BigDecimal.valueOf(windowNetTxnCount);
            case MAX_DAILY_NET_USD -> maxDailyNetUsd();
            case MAX_DAILY_NET_TXN_COUNT -> BigDecimal.valueOf(maxDailyNetTxnCount());
        };
    }

    /** Largest single-day net USD in the window; zero when the window has no rows. */
    public BigDecimal maxDailyNetUsd() {
        BigDecimal max = BigDecimal.ZERO;
        for (DayUsage d : days) {
            BigDecimal v = d.netUsd() == null ? BigDecimal.ZERO : d.netUsd();
            if (v.compareTo(max) > 0) {
                max = v;
            }
        }
        return max;
    }

    /** Largest single-day net transaction count in the window; zero when the window has no rows. */
    public long maxDailyNetTxnCount() {
        long max = 0L;
        for (DayUsage d : days) {
            if (d.netTxnCount() > max) {
                max = d.netTxnCount();
            }
        }
        return max;
    }
}
