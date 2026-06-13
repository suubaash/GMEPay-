package com.gme.pay.registry.settlement;

import com.gme.pay.contracts.SettlementPreview;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Slice 4 — projects one transaction instant through a partner's settlement
 * configuration onto a payout date, with the full human-readable derivation
 * trail (the wizard's "with these settings, your Mon 11:30 KST txn pays out
 * Wed" panel and the operator's partner-comms paste).
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li><b>Cutoff:</b> convert the transaction instant to the cutoff timezone.
 *       At or before the cutoff time, the local date is the value date; after
 *       it, the value date moves to the next calendar date.</li>
 *   <li><b>Value-date normalisation:</b> roll the value date forward over
 *       weekends and holidays — a transaction can only book value on a
 *       business day.</li>
 *   <li><b>T+N walk:</b> add {@code cycleTPlusN} BUSINESS days, where every
 *       weekend day and every holiday of EITHER calendar country (KR is always
 *       in the union — every settlement touches a Korean bank — plus the
 *       partner's bank country) is skipped.</li>
 * </ol>
 *
 * <p>Every skipped day appends one explanation line carrying the reason
 * (weekend day name, or the country-qualified holiday name straight from
 * {@code business_day_calendar.name}, V014).
 *
 * <h2>Purity</h2>
 *
 * <p>Static and side-effect free: the holiday union arrives as a prebuilt
 * {@code date -> label} map (the service merges the V014 rows; see
 * {@link SettlementConfigService#preview}), so the date arithmetic is unit
 * testable without Spring or a database — the calculator tests are the heart
 * of the Slice 4 acceptance.
 */
public final class SettlementScheduleCalculator {

    /**
     * Hard ceiling on total days walked. T+5 across the longest seeded holiday
     * block plus weekends lands well under 30; hitting 60 means the calendar
     * data is corrupt (e.g. every day seeded as a holiday) and aborting beats
     * an infinite loop.
     */
    static final int MAX_DAYS_WALKED = 60;

    private SettlementScheduleCalculator() {
        // static utility
    }

    /**
     * Project the payout date for one transaction.
     *
     * @param txnInstant  when the transaction happened (absolute instant).
     * @param cycleTPlusN settlement cycle in business days (0..5, V013 CHECK —
     *                    validated upstream by {@code SettlementConfigService}).
     * @param cutoffTime  wall-clock cutoff in {@code cutoffZone}; transactions
     *                    strictly after it book to the next value date.
     * @param cutoffZone  IANA zone the cutoff is evaluated in.
     * @param holidays    merged holiday union, {@code date -> label} where the
     *                    label is country-qualified (e.g.
     *                    {@code "KR holiday: Chuseok"}); covers every calendar
     *                    country relevant to this partner.
     * @return the projected payout date plus the explanation trail.
     * @throws IllegalStateException when no business day is found within
     *         {@link #MAX_DAYS_WALKED} days (corrupt calendar data).
     */
    public static SettlementPreview project(Instant txnInstant,
                                            int cycleTPlusN,
                                            LocalTime cutoffTime,
                                            ZoneId cutoffZone,
                                            Map<LocalDate, String> holidays) {
        List<String> trail = new ArrayList<>();
        ZonedDateTime local = txnInstant.atZone(cutoffZone);
        LocalDate valueDate = local.toLocalDate();

        if (local.toLocalTime().isAfter(cutoffTime)) {
            valueDate = valueDate.plusDays(1);
            trail.add("Transaction at " + local.toLocalTime().withNano(0) + " " + cutoffZone
                    + " on " + dayAndDate(local.toLocalDate()) + " is AFTER the " + cutoffTime
                    + " cutoff - value date moves to " + dayAndDate(valueDate) + ".");
        } else {
            trail.add("Transaction at " + local.toLocalTime().withNano(0) + " " + cutoffZone
                    + " on " + dayAndDate(local.toLocalDate()) + " is within the " + cutoffTime
                    + " cutoff - value date " + dayAndDate(valueDate) + ".");
        }

        int walked = 0;

        // Normalise the value date onto a business day (a transaction cannot
        // book value on a closed day).
        LocalDate normalised = valueDate;
        while (!isBusinessDay(normalised, holidays)) {
            trail.add(skipLine(normalised, holidays));
            normalised = normalised.plusDays(1);
            guard(++walked);
        }
        if (!normalised.equals(valueDate)) {
            trail.add("Value date rolls to " + dayAndDate(normalised) + ".");
        }

        // T+N: each cycle day advances to the NEXT business day, skipping
        // weekends and the holiday union along the way.
        LocalDate payout = normalised;
        for (int i = 0; i < cycleTPlusN; i++) {
            payout = payout.plusDays(1);
            guard(++walked);
            while (!isBusinessDay(payout, holidays)) {
                trail.add(skipLine(payout, holidays));
                payout = payout.plusDays(1);
                guard(++walked);
            }
        }

        trail.add("T+" + cycleTPlusN + " business day(s) - payout date "
                + dayAndDate(payout) + ".");
        return new SettlementPreview(payout, trail);
    }

    // -------------------------- Helpers --------------------------------------

    private static boolean isBusinessDay(LocalDate date, Map<LocalDate, String> holidays) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
                && !holidays.containsKey(date);
    }

    /** One explanation line for a skipped day, weekend reason before holiday reason. */
    private static String skipLine(LocalDate date, Map<LocalDate, String> holidays) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            // A weekend day may ALSO be a holiday (e.g. Chuseok Saturday) —
            // surface both so the trail explains the calendar fully.
            String holiday = holidays.get(date);
            return dayAndDate(date) + " skipped - weekend ("
                    + dow.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ")"
                    + (holiday == null ? "" : " and " + holiday) + ".";
        }
        return dayAndDate(date) + " skipped - " + holidays.get(date) + ".";
    }

    /** {@code "Thu 2026-09-24"} — day-of-week prefix keeps the trail scannable. */
    private static String dayAndDate(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                + " " + date;
    }

    private static void guard(int walked) {
        if (walked > MAX_DAYS_WALKED) {
            throw new IllegalStateException(
                    "no business day found within " + MAX_DAYS_WALKED
                            + " days - business_day_calendar data looks corrupt");
        }
    }
}
