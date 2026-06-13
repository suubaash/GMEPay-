package com.gme.pay.registry.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.contracts.SettlementPreview;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Slice 4 acceptance tests for {@link SettlementScheduleCalculator} — the
 * heart of the settlement preview. Pure unit tests (no Spring, no database):
 * the holiday union arrives as a prebuilt {@code date -> label} map, exactly
 * the seam {@code SettlementConfigService.preview} feeds.
 *
 * <p>Fixed instants throughout (KST = UTC+9), real 2026 calendar facts where
 * holidays matter (Chuseok 2026 = Sep 24-26, Hangul Day = Oct 9, KH Pchum Ben
 * = Oct 10-12 per V014) so these tests double as documentation of the
 * projection rules.
 */
class SettlementScheduleCalculatorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalTime CUTOFF_1630 = LocalTime.of(16, 30);
    private static final Map<LocalDate, String> NO_HOLIDAYS = Map.of();

    /** 2026-09-24..26 — the real Chuseok block (V014 seed). */
    private static Map<LocalDate, String> chuseok2026() {
        return Map.of(
                LocalDate.of(2026, 9, 24), "KR holiday: Chuseok holiday",
                LocalDate.of(2026, 9, 25), "KR holiday: Chuseok",
                LocalDate.of(2026, 9, 26), "KR holiday: Chuseok holiday");
    }

    // -------------------------------------------------------------- cutoff

    @Test
    void beforeCutoff_T1_landsNextBusinessDay() {
        // Wed 2026-06-10 10:00 KST — plain mid-week, no holidays.
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-06-10T01:00:00Z"), 1, CUTOFF_1630, SEOUL, NO_HOLIDAYS);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 6, 11));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("within the 16:30 cutoff"));
        assertThat(p.explanation().get(p.explanation().size() - 1))
                .contains("T+1").contains("2026-06-11");
    }

    @Test
    void afterCutoff_valueDateMovesForward() {
        // Wed 2026-06-10 17:00 KST — after the 16:30 cutoff.
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-06-10T08:00:00Z"), 1, CUTOFF_1630, SEOUL, NO_HOLIDAYS);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("AFTER the 16:30 cutoff").contains("2026-06-11"));
    }

    @Test
    void exactlyAtCutoff_countsAsWithin() {
        // 16:30:00 KST on the dot — the boundary books same-day (only STRICTLY
        // after moves the value date).
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-06-10T07:30:00Z"), 0, CUTOFF_1630, SEOUL, NO_HOLIDAYS);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 6, 10));
    }

    @Test
    void cutoffIsEvaluatedInTheConfiguredZone() {
        // Same instant, different cutoff zones, different verdicts:
        // 2026-06-10T08:00Z = 17:00 Seoul (AFTER 16:30) = 15:00 Phnom Penh (within).
        Instant txn = Instant.parse("2026-06-10T08:00:00Z");

        SettlementPreview seoul = SettlementScheduleCalculator.project(
                txn, 0, CUTOFF_1630, SEOUL, NO_HOLIDAYS);
        SettlementPreview phnomPenh = SettlementScheduleCalculator.project(
                txn, 0, CUTOFF_1630, ZoneId.of("Asia/Phnom_Penh"), NO_HOLIDAYS);

        assertThat(seoul.payoutDate()).isEqualTo(LocalDate.of(2026, 6, 11));
        assertThat(phnomPenh.payoutDate()).isEqualTo(LocalDate.of(2026, 6, 10));
    }

    // -------------------------------------------------------------- weekends

    @Test
    void weekendRoll_fridayT1_paysMonday() {
        // Fri 2026-06-12 10:00 KST, T+1 — Sat/Sun skipped with reasons.
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-06-12T01:00:00Z"), 1, CUTOFF_1630, SEOUL, NO_HOLIDAYS);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("2026-06-13").contains("Saturday"));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("2026-06-14").contains("Sunday"));
    }

    @Test
    void tPlusZero_onWeekend_rollsValueDateToMonday() {
        // Sat 2026-06-13 10:00 KST, T+0 — the value date itself must land on a
        // business day before any cycle days are added.
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-06-13T01:00:00Z"), 0, CUTOFF_1630, SEOUL, NO_HOLIDAYS);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("Value date rolls to"));
    }

    @Test
    void tPlusZero_beforeCutoffOnBusinessDay_paysSameDay() {
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-06-10T01:00:00Z"), 0, CUTOFF_1630, SEOUL, NO_HOLIDAYS);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(p.explanation()).hasSize(2); // cutoff verdict + final landing
    }

    @Test
    void tPlusThree_spansOneWeekend() {
        // Thu 2026-06-11 10:00 KST, T+3: Fri 12 -> Mon 15 -> Tue 16.
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-06-11T01:00:00Z"), 3, CUTOFF_1630, SEOUL, NO_HOLIDAYS);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 6, 16));
    }

    // -------------------------------------------------------------- holidays

    @Test
    void singleHoliday_isSkippedWithItsName() {
        // Thu 2026-10-08 10:00 KST, T+1; Fri Oct 9 is Hangul Day -> payout Mon.
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-10-08T01:00:00Z"), 1, CUTOFF_1630, SEOUL,
                Map.of(LocalDate.of(2026, 10, 9), "KR holiday: Hangul Day"));

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("2026-10-09").contains("KR holiday: Hangul Day"));
    }

    @Test
    void chuseokBlock_afterCutoffWednesday_T1_paysTuesday() {
        // The plan's exit-gate example: txn Wed 2026-09-23 17:30 KST (after
        // cutoff) -> value date Thu Sep 24 = Chuseok -> block 24/25/26 + the
        // weekend roll the value date all the way to Mon Sep 28; T+1 -> Tue 29.
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-09-23T08:30:00Z"), 1, CUTOFF_1630, SEOUL, chuseok2026());

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("2026-09-24").contains("Chuseok"));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("Value date rolls to").contains("2026-09-28"));
        // The Chuseok Saturday surfaces BOTH reasons.
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("2026-09-26").contains("Saturday").contains("Chuseok"));
    }

    @Test
    void crossCountryUnion_krPlusKh_skipsBothCalendars() {
        // Thu 2026-10-08 10:00 KST, T+1. KR closes Fri Oct 9 (Hangul Day); KH
        // closes Oct 10-12 (Pchum Ben). The union pushes payout to Tue Oct 13.
        Map<LocalDate, String> union = new HashMap<>();
        union.put(LocalDate.of(2026, 10, 9), "KR holiday: Hangul Day");
        union.put(LocalDate.of(2026, 10, 10), "KH holiday: Pchum Ben");
        union.put(LocalDate.of(2026, 10, 11), "KH holiday: Pchum Ben");
        union.put(LocalDate.of(2026, 10, 12), "KH holiday: Pchum Ben");

        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-10-08T01:00:00Z"), 1, CUTOFF_1630, SEOUL, union);

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("Hangul Day"));
        // The KH-only Monday is what separates the union from KR-alone (which
        // would have paid out Mon Oct 12).
        assertThat(p.explanation()).anySatisfy(line ->
                assertThat(line).contains("2026-10-12").contains("KH holiday: Pchum Ben"));
    }

    @Test
    void tPlusZero_afterCutoffIntoHolidayBlock_rollsThroughIt() {
        // T+0 partner, txn after cutoff on Chuseok eve: value date enters the
        // block and must roll to the first business day after it.
        SettlementPreview p = SettlementScheduleCalculator.project(
                Instant.parse("2026-09-23T08:30:00Z"), 0, CUTOFF_1630, SEOUL, chuseok2026());

        assertThat(p.payoutDate()).isEqualTo(LocalDate.of(2026, 9, 28));
    }

    // -------------------------------------------------------------- guard

    @Test
    void corruptCalendar_everyDayHoliday_abortsInsteadOfLooping() {
        Map<LocalDate, String> everyDay = new HashMap<>();
        LocalDate d = LocalDate.of(2026, 6, 1);
        for (int i = 0; i < 100; i++) {
            everyDay.put(d.plusDays(i), "XX holiday: corrupt seed");
        }

        assertThatThrownBy(() -> SettlementScheduleCalculator.project(
                Instant.parse("2026-06-10T01:00:00Z"), 1, CUTOFF_1630, SEOUL, everyDay))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("business_day_calendar");
    }
}
