package com.gme.pay.settlement.calendar;

/**
 * What the configured business-day calendar can say about one date.
 *
 * <p>The third value is the whole point of gap <b>T3-4</b>. Before this existed the schedulers fired on a
 * fixed cron and every day was <em>implicitly</em> a KRW banking day; a run on a Korean bank holiday would
 * hand ZeroPay a file it may reject or double-count, and nothing anywhere said so. There are therefore
 * three states, not two: a date can be known-closed, known-open, or <b>not covered by any calendar data at
 * all</b> — and the last one must be visible rather than silently treated as "open".
 *
 * @see BusinessCalendar
 */
public enum BusinessDayVerdict {

    /** The date is covered by configured calendar data and is a business day — safe to run. */
    BUSINESS_DAY,

    /** The date is explicitly configured as a non-business date (or a non-business day-of-week) — skip. */
    NON_BUSINESS_DAY,

    /**
     * No calendar data covers this date. The run proceeds (fail-open) unless
     * {@code gmepay.calendar.fail-closed=true}, but it is recorded on the run row and raised as a
     * {@code BATCH_CALENDAR_UNVERIFIED} ops alert, so "we ran on an unverified business day" is a
     * visible operational fact instead of a silent assumption.
     */
    UNVERIFIED;

    /** True when the calendar positively knows the date is closed. */
    public boolean isNonBusinessDay() {
        return this == NON_BUSINESS_DAY;
    }

    /** True when no configured calendar data covers the date. */
    public boolean isUnverified() {
        return this == UNVERIFIED;
    }
}
