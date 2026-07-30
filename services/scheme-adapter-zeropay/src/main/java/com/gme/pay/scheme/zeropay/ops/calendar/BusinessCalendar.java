package com.gme.pay.scheme.zeropay.ops.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The injectable, configurable business-day calendar the ZeroPay ZP00xx batch scheduler consults (gap
 * <b>T3-4</b>: "holidays ignored").
 *
 * <p><b>Deliberate duplicate.</b> This is a copy of
 * {@code com.gme.pay.settlement.calendar.BusinessCalendar}, with the same property names so ONE set of
 * {@code gmepay.calendar.*} values configures both services identically. It is duplicated rather than shared
 * because the natural home ({@code libs/}) was owned by a concurrent workstream when this landed; promoting
 * it to {@code libs/lib-errors} (which already hosts the shared platform seams) is a recorded follow-up. The
 * two copies MUST be kept in step — {@code scripts/check_batch_ops_wiring.py} asserts they agree.
 *
 * <h2>What this deliberately is NOT</h2>
 * <p>It contains <b>no Korean holiday data whatsoever</b>, and no rule for deriving one. Korean banking
 * holidays are a lunar-calendar plus annually-gazetted set (Seollal, Chuseok, substitute holidays,
 * temporary public holidays announced weeks ahead); any table hardcoded here would be wrong for some year
 * and would be believed anyway because it looked authoritative. So the calendar data is an
 * <b>operator/business input</b> — see {@code Documentation/RUNBOOK_BATCH_OPS.md} — and this class is only
 * the seam that reads it and the machinery that makes its <em>absence</em> loud.
 *
 * <h2>Configuration</h2>
 * <pre>
 * gmepay.calendar.non-business-dates        = 2026-01-01,2026-02-17   (ISO dates; EMPTY by default)
 * gmepay.calendar.non-business-days-of-week = SATURDAY,SUNDAY         (EMPTY by default)
 * gmepay.calendar.verified-through          = 2026-12-31              (UNSET by default)
 * gmepay.calendar.fail-closed               = false                   (default: proceed + alert)
 * </pre>
 *
 * <p>Every default is "no data". A fresh deployment therefore answers {@link BusinessDayVerdict#UNVERIFIED}
 * for every date, which is the honest answer — it does <em>not</em> answer BUSINESS_DAY, because nobody has
 * told it what the business days are.
 *
 * <h2>Why {@code verified-through} exists</h2>
 * <p>Without it, a calendar populated for 2026 would keep silently answering BUSINESS_DAY for every day of
 * 2027 — "not in my holiday list" read as "definitely open", which is exactly the class of silent
 * assumption this gap is about. {@code verified-through} is the operator asserting the coverage horizon:
 * dates past it are UNVERIFIED even when the holiday list is non-empty, so the calendar going stale
 * surfaces as an alert instead of as wrong files.
 *
 * <p>Weekends are configuration too, not a built-in. KRW banking is in fact closed at weekends, but which
 * of the six batch windows an operator wants suppressed on a Saturday is a business decision, so it stays
 * an explicit setting with an empty default.
 *
 * <h2>Behaviour on UNVERIFIED</h2>
 * <p>Fail-OPEN by default: the run proceeds, because a platform that refuses to settle until somebody
 * types in a holiday table is worse than one that settles and says so. The verdict is stamped on the
 * durable run row and raised as a {@code BATCH_CALENDAR_UNVERIFIED} ops alert. Set
 * {@code gmepay.calendar.fail-closed=true} once the calendar is genuinely maintained to invert that.
 */
@Component
public class BusinessCalendar {

    private static final Logger log = LoggerFactory.getLogger(BusinessCalendar.class);

    private final Set<LocalDate> nonBusinessDates;
    private final Set<DayOfWeek> nonBusinessDaysOfWeek;
    private final LocalDate verifiedThrough;
    private final boolean failClosed;

    public BusinessCalendar(
            @Value("${gmepay.calendar.non-business-dates:}") List<String> nonBusinessDates,
            @Value("${gmepay.calendar.non-business-days-of-week:}") List<String> nonBusinessDaysOfWeek,
            @Value("${gmepay.calendar.verified-through:}") String verifiedThrough,
            @Value("${gmepay.calendar.fail-closed:false}") boolean failClosed) {
        this.nonBusinessDates = Collections.unmodifiableSet(parseDates(nonBusinessDates));
        this.nonBusinessDaysOfWeek = Collections.unmodifiableSet(parseDaysOfWeek(nonBusinessDaysOfWeek));
        this.verifiedThrough = parseDate(verifiedThrough);
        this.failClosed = failClosed;
        logConfiguredState();
    }

    /** Test/programmatic construction with an explicit calendar. */
    public BusinessCalendar(Set<LocalDate> nonBusinessDates,
                            Set<DayOfWeek> nonBusinessDaysOfWeek,
                            LocalDate verifiedThrough,
                            boolean failClosed) {
        this.nonBusinessDates = Set.copyOf(nonBusinessDates);
        this.nonBusinessDaysOfWeek = Set.copyOf(nonBusinessDaysOfWeek);
        this.verifiedThrough = verifiedThrough;
        this.failClosed = failClosed;
    }

    /** An empty calendar — every date UNVERIFIED, fail-open. The shipped default. */
    public static BusinessCalendar empty() {
        return new BusinessCalendar(Set.of(), Set.of(), null, false);
    }

    /**
     * Classify one date.
     *
     * <p>Order matters: an explicitly configured non-business date wins even when it falls past
     * {@code verified-through}, because "the operator told us this specific day is closed" is stronger
     * information than "our coverage horizon has passed".
     */
    public BusinessDayVerdict classify(LocalDate date) {
        if (date == null) {
            return BusinessDayVerdict.UNVERIFIED;
        }
        if (nonBusinessDates.contains(date) || nonBusinessDaysOfWeek.contains(date.getDayOfWeek())) {
            return BusinessDayVerdict.NON_BUSINESS_DAY;
        }
        if (!hasData()) {
            return BusinessDayVerdict.UNVERIFIED;   // nothing configured — we know nothing about any date
        }
        if (verifiedThrough == null || date.isAfter(verifiedThrough)) {
            return BusinessDayVerdict.UNVERIFIED;   // outside the operator-asserted coverage horizon
        }
        return BusinessDayVerdict.BUSINESS_DAY;
    }

    /**
     * Gate one job for one date: throws when the date is a configured non-business date, or when it is
     * UNVERIFIED <em>and</em> {@code fail-closed} is on.
     *
     * <p>Returns the verdict so the caller can stamp it on the run record and alert on UNVERIFIED. This is
     * the single enforcement point — the schedulers, the settlement window/cutoff logic and the operator
     * re-run path all reach it, so no path can bypass the calendar.
     *
     * @throws NonBusinessDayException when the date must not be processed
     */
    public BusinessDayVerdict gate(LocalDate date, String what) {
        BusinessDayVerdict verdict = classify(date);
        if (verdict.isNonBusinessDay()) {
            throw new NonBusinessDayException(date, what);
        }
        if (verdict.isUnverified() && failClosed) {
            throw new NonBusinessDayException(date, what + " (calendar UNVERIFIED and "
                    + "gmepay.calendar.fail-closed=true)");
        }
        return verdict;
    }

    /** True when ANY calendar data is configured. False on a fresh deployment. */
    public boolean hasData() {
        return !nonBusinessDates.isEmpty() || !nonBusinessDaysOfWeek.isEmpty();
    }

    /** Operator-asserted coverage horizon, or null when unset. */
    public LocalDate getVerifiedThrough() {
        return verifiedThrough;
    }

    public boolean isFailClosed() {
        return failClosed;
    }

    public Set<LocalDate> getNonBusinessDates() {
        return nonBusinessDates;
    }

    public Set<DayOfWeek> getNonBusinessDaysOfWeek() {
        return nonBusinessDaysOfWeek;
    }

    /** One-line human summary, used in the alert detail and the startup log. */
    public String describe() {
        return "nonBusinessDates=" + nonBusinessDates.size()
                + ", nonBusinessDaysOfWeek=" + nonBusinessDaysOfWeek
                + ", verifiedThrough=" + (verifiedThrough == null ? "UNSET" : verifiedThrough)
                + ", failClosed=" + failClosed;
    }

    private void logConfiguredState() {
        if (!hasData()) {
            log.warn("BusinessCalendar: NO calendar data configured — every batch date will classify as "
                    + "UNVERIFIED and each day's first run will raise a BATCH_CALENDAR_UNVERIFIED ops alert. "
                    + "Populating gmepay.calendar.non-business-dates is an operator/business input; see "
                    + "Documentation/RUNBOOK_BATCH_OPS.md");
            return;
        }
        log.info("BusinessCalendar configured: {}", describe());
        if (verifiedThrough == null) {
            log.warn("BusinessCalendar: gmepay.calendar.verified-through is UNSET — dates are UNVERIFIED even "
                    + "though {} non-business date(s) are configured, because no coverage horizon was asserted",
                    nonBusinessDates.size());
        } else if (verifiedThrough.isBefore(LocalDate.now())) {
            log.warn("BusinessCalendar: gmepay.calendar.verified-through={} is in the PAST — the calendar is "
                    + "stale and every current date classifies UNVERIFIED", verifiedThrough);
        }
    }

    /** Parse ISO dates; a malformed entry is dropped LOUDLY rather than failing the boot of the money path. */
    private static Set<LocalDate> parseDates(List<String> raw) {
        Set<LocalDate> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            LocalDate d = parseDate(s);
            if (d == null) {
                log.error("BusinessCalendar: ignoring unparseable non-business date '{}' "
                        + "(expected ISO yyyy-MM-dd) — that date will classify as UNVERIFIED, not closed",
                        s.trim());
                continue;
            }
            out.add(d);
        }
        return out;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Set<DayOfWeek> parseDaysOfWeek(List<String> raw) {
        Set<DayOfWeek> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                out.add(DayOfWeek.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.error("BusinessCalendar: ignoring unknown day-of-week '{}' (expected e.g. SATURDAY)",
                        s.trim());
            }
        }
        return out;
    }
}
