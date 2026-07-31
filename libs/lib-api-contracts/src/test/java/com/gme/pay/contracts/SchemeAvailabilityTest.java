package com.gme.pay.contracts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T3-6 operating-window evaluation — the one place the {@code scheme_operating_hours} (V024)
 * timezone arithmetic lives, so it is pinned here rather than through two services' HTTP stacks.
 *
 * <p>Every case is anchored to a real UTC instant with a known weekday, so "the test passes because it
 * happens to be Tuesday" is impossible.
 */
class SchemeAvailabilityTest {

    /** 2026-07-28 is a TUESDAY → V024 weekday 1 (0 = Monday). */
    private static final Instant TUE_2026_07_28_0300Z = Instant.parse("2026-07-28T03:00:00Z");

    private static SchemeOperatingHoursView row(int weekday, String open, String close,
                                                String cutoff, String zone) {
        return new SchemeOperatingHoursView("TESTSCHEME", weekday,
                LocalTime.parse(open), LocalTime.parse(close),
                cutoff == null ? null : LocalTime.parse(cutoff), zone);
    }

    // ------------------------------------------------------------------ OPEN

    @Test
    void insideTheWindow_isOpen() {
        // 03:00Z = 12:00 KST Tuesday; window 09:00-18:00 Seoul on weekday 1.
        SchemeAvailability a = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(row(1, "09:00", "18:00", null, "Asia/Seoul")), TUE_2026_07_28_0300Z);

        assertEquals(SchemeAvailabilityVerdict.OPEN, a.verdict());
        assertTrue(a.open());
        assertEquals(LocalTime.of(12, 0), a.localTime());
        assertEquals("Asia/Seoul", a.timezone());
        assertEquals(1, a.weekday());
    }

    @Test
    void the24x7SeedShape_isOpenEvenInTheLastSecondOfTheDay() {
        // V024 seeds 24x7 rails as 00:00:00-23:59:59 (TIME '24:00' is not portable), so the window test
        // MUST be inclusive at both ends or every rail would read as closed at 23:59:59.
        Instant lastSecondKst = Instant.parse("2026-07-28T14:59:59Z"); // 23:59:59 KST
        SchemeAvailability a = SchemeAvailability.evaluate("ZEROPAY",
                List.of(row(1, "00:00:00", "23:59:59", "16:30", "Asia/Seoul")), lastSecondKst);

        assertTrue(a.open(), a.reason());
    }

    // ---------------------------------------------------------------- CLOSED

    @Test
    void outsideTheWindow_isClosed_andSaysWhen() {
        // 03:00Z = 12:00 KST; window 18:00-22:00 Seoul.
        SchemeAvailability a = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(row(1, "18:00", "22:00", null, "Asia/Seoul")), TUE_2026_07_28_0300Z);

        assertEquals(SchemeAvailabilityVerdict.CLOSED, a.verdict());
        assertTrue(a.closed());
        assertTrue(a.reason().contains("12:00"), a.reason());
        assertTrue(a.reason().contains("Asia/Seoul"), a.reason());
    }

    @Test
    void overnightWindow_isOpenOnBothSidesOfMidnight_notTreatedAsEmpty() {
        // Window 22:00-06:00 (close BEFORE open) must be the union of the two day-parts.
        // 2026-07-28T14:30Z = 23:30 KST Tuesday (weekday 1).
        SchemeAvailability lateEvening = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(row(1, "22:00", "06:00", null, "Asia/Seoul")),
                Instant.parse("2026-07-28T14:30:00Z"));
        assertTrue(lateEvening.open(), lateEvening.reason());

        // 2026-07-28T20:30Z = 05:30 KST WEDNESDAY (weekday 2).
        SchemeAvailability earlyMorning = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(row(2, "22:00", "06:00", null, "Asia/Seoul")),
                Instant.parse("2026-07-28T20:30:00Z"));
        assertTrue(earlyMorning.open(), earlyMorning.reason());

        // Mid-afternoon KST is outside both parts.
        SchemeAvailability afternoon = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(row(1, "22:00", "06:00", null, "Asia/Seoul")), TUE_2026_07_28_0300Z);
        assertTrue(afternoon.closed(), afternoon.reason());
    }

    // ------------------------------------------------------------- TIMEZONE

    @Test
    void theRowsTimezoneDecidesTheWEEKDAY_notTheServersLocalDate() {
        // 2026-07-27 is a MONDAY. At 23:30Z the calendar day has already turned in Asia/Seoul (UTC+9):
        // TUESDAY 08:30, weekday 1. In America/New_York (UTC-4) it is still MONDAY 19:30, weekday 0.
        // One instant, two different weekday ROWS — which is the whole reason the server's zone is
        // never consulted.
        Instant at = Instant.parse("2026-07-27T23:30:00Z");

        // The Seoul row for TUESDAY applies and is open 08:00-09:00.
        SchemeAvailability seoul = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(row(1, "08:00", "09:00", null, "Asia/Seoul")), at);
        assertTrue(seoul.open(), seoul.reason());
        assertEquals(1, seoul.weekday());
        assertEquals(LocalTime.of(8, 30), seoul.localTime());

        // The very same instant, evaluated for a New York scheme, is the PREVIOUS weekday: a MONDAY row
        // applies (and the TUESDAY row does not exist yet), which is why a server-local weekday would
        // have picked the wrong row entirely.
        SchemeAvailability newYork = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(row(0, "19:00", "20:00", null, "America/New_York")), at);
        assertTrue(newYork.open(), newYork.reason());
        assertEquals(0, newYork.weekday());
        assertEquals(LocalTime.of(19, 30), newYork.localTime());
    }

    @Test
    void aTuesdayRowDoesNotCoverTheSchemesLocalMonday() {
        // 2026-07-27T23:30Z is TUESDAY in Seoul but MONDAY in New York: a schedule seeded only for
        // weekday 1 leaves the New York scheme's Monday UNVERIFIED — it is NOT silently open, and it is
        // NOT reported closed either.
        SchemeAvailability a = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(row(1, "00:00", "23:59:59", null, "America/New_York")),
                Instant.parse("2026-07-27T23:30:00Z"));

        assertEquals(SchemeAvailabilityVerdict.UNVERIFIED, a.verdict());
        assertTrue(a.reason().contains("NOT assumed open"), a.reason());
    }

    // ----------------------------------------------------------- UNVERIFIED

    @Test
    void noRowsAtAll_isUnverified_neverOpen() {
        SchemeAvailability a = SchemeAvailability.evaluate("QRIS", List.of(), TUE_2026_07_28_0300Z);

        assertEquals(SchemeAvailabilityVerdict.UNVERIFIED, a.verdict());
        assertFalse(a.open());
        assertFalse(a.closed());
        assertNull(a.timezone());
        assertEquals(SchemeAvailability.NO_WEEKDAY, a.weekday());
        assertTrue(a.reason().contains("V024"), a.reason());
    }

    @Test
    void nullRows_nullScheme_nullInstant_areAllUnverified() {
        assertTrue(SchemeAvailability.evaluate("X", null, TUE_2026_07_28_0300Z).unverified());
        assertTrue(SchemeAvailability.evaluate(null, List.of(), TUE_2026_07_28_0300Z).unverified());
        assertTrue(SchemeAvailability.evaluate("  ", List.of(), TUE_2026_07_28_0300Z).unverified());
        assertTrue(SchemeAvailability.evaluate("X",
                List.of(row(1, "09:00", "18:00", null, "Asia/Seoul")), null).unverified());
    }

    @Test
    void unusableRow_badZoneOrNullTimes_isUnverified_notClosed() {
        SchemeAvailability badZone = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(row(1, "09:00", "18:00", null, "Not/AZone")), TUE_2026_07_28_0300Z);
        assertEquals(SchemeAvailabilityVerdict.UNVERIFIED, badZone.verdict());
        assertTrue(badZone.reason().contains("unusable"), badZone.reason());

        SchemeAvailability nullTimes = SchemeAvailability.evaluate("TESTSCHEME",
                List.of(new SchemeOperatingHoursView("TESTSCHEME", 1, null, null, null, "Asia/Seoul")),
                TUE_2026_07_28_0300Z);
        assertEquals(SchemeAvailabilityVerdict.UNVERIFIED, nullTimes.verdict());
    }

    // --------------------------------------------------------------- CUTOFF

    @Test
    void cutoffIsNotAClose_pastCutoffStaysOpen() {
        // ZEROPAY's seeded shape: 24x7 with a 16:30 KST KFTC settlement cutoff. 2026-07-28T09:00Z is
        // 18:00 KST — PAST the cutoff. Collapsing cutoff into close would shut the platform's only live
        // corridor for 7.5 hours a day.
        SchemeAvailability a = SchemeAvailability.evaluate("ZEROPAY",
                List.of(row(1, "00:00:00", "23:59:59", "16:30", "Asia/Seoul")),
                Instant.parse("2026-07-28T09:00:00Z"));

        assertEquals(SchemeAvailabilityVerdict.OPEN, a.verdict());
        assertTrue(a.hasCutoff());
        assertTrue(a.pastCutoff());
        assertEquals(LocalTime.of(16, 30), a.cutoffTimeLocal());
    }

    @Test
    void beforeCutoff_isNotPastCutoff_andNoCutoffMeansNeverPast() {
        SchemeAvailability before = SchemeAvailability.evaluate("ZEROPAY",
                List.of(row(1, "00:00:00", "23:59:59", "16:30", "Asia/Seoul")),
                TUE_2026_07_28_0300Z); // 12:00 KST
        assertFalse(before.pastCutoff());

        // NAPAS_247 / PROMPT_PAY / FAST_SG are seeded with a NULL cutoff.
        SchemeAvailability noCutoff = SchemeAvailability.evaluate("NAPAS_247",
                List.of(row(1, "00:00:00", "23:59:59", null, "Asia/Ho_Chi_Minh")),
                TUE_2026_07_28_0300Z);
        assertFalse(noCutoff.hasCutoff());
        assertFalse(noCutoff.pastCutoff());
        assertTrue(noCutoff.open());
    }

    // ------------------------------------------------- multi-row edge cases

    @Test
    void aFullWeekOfRowsPicksExactlyTheLocalWeekdaysRow() {
        // Seven rows, only Tuesday(1) open; the instant is Tuesday 12:00 KST.
        List<SchemeOperatingHoursView> week = List.of(
                row(0, "09:00", "10:00", null, "Asia/Seoul"),
                row(1, "09:00", "18:00", null, "Asia/Seoul"),
                row(2, "09:00", "10:00", null, "Asia/Seoul"),
                row(3, "09:00", "10:00", null, "Asia/Seoul"),
                row(4, "09:00", "10:00", null, "Asia/Seoul"),
                row(5, "09:00", "10:00", null, "Asia/Seoul"),
                row(6, "09:00", "10:00", null, "Asia/Seoul"));

        SchemeAvailability tuesday = SchemeAvailability.evaluate("TESTSCHEME", week, TUE_2026_07_28_0300Z);
        assertTrue(tuesday.open(), tuesday.reason());
        assertEquals(1, tuesday.weekday());

        // Wednesday 12:00 KST → the 09:00-10:00 row → closed.
        SchemeAvailability wednesday = SchemeAvailability.evaluate("TESTSCHEME", week,
                Instant.parse("2026-07-29T03:00:00Z"));
        assertTrue(wednesday.closed(), wednesday.reason());
        assertEquals(2, wednesday.weekday());
    }
}
