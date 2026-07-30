package com.gme.pay.contracts;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * "Can this transaction route to this scheme NOW?" — the pure evaluation of a scheme's
 * {@code scheme_operating_hours} weekly schedule (V024) at an instant. Gap <b>T3-6</b>.
 *
 * <p>V024's own migration header states the purpose of the table: <i>"The router and the settlement
 * calculator need this to decide 'can this transaction route NOW?' and 'which value date does it book
 * to?'"</i>. Until T3-6 the table had zero consumers. This record is the single answer to the first
 * question, shared by every consumer (payment-executor's authorize + wallet gates, smart-router's
 * location resolution) so the timezone arithmetic exists in exactly ONE place and is tested once.
 *
 * <h2>Why the evaluation is here and not in config-registry</h2>
 * The rows are migration-seeded reference data, so the read is cacheable and the evaluation is a pure
 * function of (rows, instant). Putting the function in the contracts library means a consumer that has
 * already cached the 7 rows can answer "open now?" without a per-payment round trip to config-registry
 * — and, critically, means a config-registry blip degrades to {@link SchemeAvailabilityVerdict#UNVERIFIED}
 * (visible, permissive) rather than to a wrong OPEN or a blocked corridor.
 *
 * <h2>Timezone: each ROW's zone, never the server's</h2>
 * Every row carries its own IANA {@code timezone}, and the weekday + wall-clock comparison are done in
 * THAT zone. A server in Asia/Seoul evaluating a {@code Asia/Phnom_Penh} scheme at 00:30 KST is
 * evaluating 22:30 the PREVIOUS day in Phnom Penh, and therefore the PREVIOUS weekday's row. The
 * server's own zone (and {@code LocalTime.now()}) is never consulted.
 *
 * <h2>Cutoff is NOT a close (task 6)</h2>
 * {@code cutoff_time_local} and {@code close_time_local} mean different things and are deliberately
 * NOT collapsed. ZEROPAY is seeded as a 24x7 rail (00:00:00–23:59:59 Asia/Seoul) with a
 * {@code cutoff_time_local} of 16:30 KST — the KFTC interbank <i>settlement</i> cutoff, i.e. the
 * "which value date does it book to?" half of V024's purpose. Treating that cutoff as a close would
 * shut the platform's only live corridor down for 7.5 hours a day. So:
 * <ul>
 *   <li>{@link #verdict()} is decided by open/close ONLY — a cutoff never closes a scheme and never
 *       rejects a payment.</li>
 *   <li>{@link #pastCutoff()} reports the settlement-eligibility fact for observability and for a
 *       future value-date decision. It matches the shape of the per-partner settlement cutoff already
 *       modelled in config-registry ({@code SettlementConfigService.DEFAULT_CUTOFF_TIME} = 16:30
 *       Asia/Seoul, V013) — which this work deliberately does not change.</li>
 * </ul>
 *
 * @param schemeId       the scheme evaluated (as supplied; never null on a real evaluation)
 * @param verdict        OPEN / CLOSED / UNVERIFIED — see {@link SchemeAvailabilityVerdict}
 * @param weekday        the weekday the decision used, {@code 0}=Monday..{@code 6}=Sunday, in
 *                       {@link #timezone}; {@code -1} when UNVERIFIED with no usable row
 * @param localTime      the scheme-local wall-clock time the decision used; null when UNVERIFIED with
 *                       no usable row
 * @param timezone       the IANA zone the decision was made in (the ROW's zone); null when unverified
 * @param openTimeLocal  the matched row's window open; null when UNVERIFIED
 * @param closeTimeLocal the matched row's window close; null when UNVERIFIED
 * @param cutoffTimeLocal the matched row's settlement cutoff; null when the scheme has none (or UNVERIFIED)
 * @param pastCutoff     true when a cutoff IS configured and {@link #localTime} is at/after it
 * @param reason         short human-readable explanation, safe to put in a log line or an error message
 */
public record SchemeAvailability(
        String schemeId,
        SchemeAvailabilityVerdict verdict,
        int weekday,
        LocalTime localTime,
        String timezone,
        LocalTime openTimeLocal,
        LocalTime closeTimeLocal,
        LocalTime cutoffTimeLocal,
        boolean pastCutoff,
        String reason) {

    /** Sentinel weekday for an evaluation that never resolved a row. */
    public static final int NO_WEEKDAY = -1;

    /**
     * Evaluate a scheme's weekly schedule at {@code at}.
     *
     * <p>Never throws and never returns null: every degenerate input (null scheme, null/empty rows, a
     * row with an unparseable zone or null times, no row for the local weekday) yields
     * {@link SchemeAvailabilityVerdict#UNVERIFIED} with a reason — because the one answer this method
     * must never invent is "open".
     *
     * <p>When several rows are applicable — only possible if a scheme's rows disagree about
     * {@code timezone} such that two zones sit on different weekdays at this instant — the evaluation
     * is <b>most permissive</b>: if ANY applicable row's window contains its own local time the scheme
     * is OPEN. A single-timezone scheme (every row seeded in V024) has exactly one applicable row and
     * this rule is inert.
     *
     * @param schemeId       the scheme being evaluated (used only for the reason text)
     * @param weeklySchedule the scheme's rows (any order, any subset of weekdays); null/empty allowed
     * @param at             the instant to evaluate; null is treated as unverifiable
     */
    public static SchemeAvailability evaluate(String schemeId,
                                             List<SchemeOperatingHoursView> weeklySchedule,
                                             Instant at) {
        if (schemeId == null || schemeId.isBlank()) {
            return unverified(schemeId, "no scheme reference supplied — nothing to evaluate");
        }
        if (at == null) {
            return unverified(schemeId, "no evaluation instant supplied");
        }
        if (weeklySchedule == null || weeklySchedule.isEmpty()) {
            return unverified(schemeId, "no scheme_operating_hours row seeded for scheme '"
                    + schemeId + "' (V024 reference data missing)");
        }

        SchemeAvailability closedCandidate = null;
        int usableRows = 0;
        for (SchemeOperatingHoursView row : weeklySchedule) {
            ZoneId zone = zoneOf(row);
            if (zone == null || row.openTimeLocal() == null || row.closeTimeLocal() == null) {
                // Unusable row: it can make nothing OPEN and, just as importantly, nothing CLOSED.
                continue;
            }
            usableRows++;
            ZonedDateTime local = at.atZone(zone);
            if (weekdayOf(local) != row.weekday()) {
                continue; // a different weekday's row in this row's own zone
            }
            LocalTime nowLocal = local.toLocalTime();
            boolean open = within(nowLocal, row.openTimeLocal(), row.closeTimeLocal());
            SchemeAvailability decided = new SchemeAvailability(
                    schemeId,
                    open ? SchemeAvailabilityVerdict.OPEN : SchemeAvailabilityVerdict.CLOSED,
                    row.weekday(),
                    nowLocal,
                    zone.getId(),
                    row.openTimeLocal(),
                    row.closeTimeLocal(),
                    row.cutoffTimeLocal(),
                    row.cutoffTimeLocal() != null && !nowLocal.isBefore(row.cutoffTimeLocal()),
                    (open ? "scheme '" + schemeId + "' is OPEN" : "scheme '" + schemeId + "' is CLOSED")
                            + " at " + nowLocal + " " + zone.getId()
                            + " (window " + row.openTimeLocal() + "–" + row.closeTimeLocal()
                            + ", weekday " + row.weekday() + ")");
            if (open) {
                return decided; // most permissive wins
            }
            if (closedCandidate == null) {
                closedCandidate = decided;
            }
        }
        if (closedCandidate != null) {
            return closedCandidate;
        }
        if (usableRows == 0) {
            return unverified(schemeId, "every scheme_operating_hours row for scheme '" + schemeId
                    + "' is unusable (blank/unknown timezone or null open/close time)");
        }
        return unverified(schemeId, "no scheme_operating_hours row for scheme '" + schemeId
                + "' covers the current scheme-local weekday — window unknown, NOT assumed open");
    }

    /** True when the scheme is affirmatively open. */
    public boolean open() {
        return verdict == SchemeAvailabilityVerdict.OPEN;
    }

    /** True when a row exists and affirmatively excludes now — the ONLY rejection case. */
    public boolean closed() {
        return verdict == SchemeAvailabilityVerdict.CLOSED;
    }

    /** True when the window could not be established from reference data (never treated as open). */
    public boolean unverified() {
        return verdict == SchemeAvailabilityVerdict.UNVERIFIED;
    }

    /** True when this scheme declares an intra-day settlement cutoff at all. */
    public boolean hasCutoff() {
        return cutoffTimeLocal != null;
    }

    private static SchemeAvailability unverified(String schemeId, String reason) {
        return new SchemeAvailability(schemeId, SchemeAvailabilityVerdict.UNVERIFIED,
                NO_WEEKDAY, null, null, null, null, null, false, reason);
    }

    /** {@code 0}=Monday..{@code 6}=Sunday — the V024 weekday convention. */
    private static int weekdayOf(ZonedDateTime local) {
        DayOfWeek day = local.getDayOfWeek();
        return day.getValue() - 1;
    }

    private static ZoneId zoneOf(SchemeOperatingHoursView row) {
        if (row == null || row.timezone() == null || row.timezone().isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(row.timezone().trim());
        } catch (RuntimeException badZone) {
            return null;
        }
    }

    /**
     * Inclusive-at-both-ends window test. Inclusive because V024 seeds a 24x7 rail as
     * {@code 00:00:00–23:59:59} (TIME '24:00' is not portable to H2), so an exclusive close would
     * report the last second of every day as closed. An overnight window (close before open, e.g.
     * 22:00–06:00) is handled as the union of the two day-parts rather than as an empty window.
     */
    private static boolean within(LocalTime now, LocalTime open, LocalTime close) {
        if (!close.isBefore(open)) {
            return !now.isBefore(open) && !now.isAfter(close);
        }
        return !now.isBefore(open) || !now.isAfter(close);
    }
}
