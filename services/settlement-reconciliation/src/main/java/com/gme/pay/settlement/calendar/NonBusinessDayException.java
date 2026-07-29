package com.gme.pay.settlement.calendar;

import java.time.LocalDate;

/**
 * Thrown when a settlement window is asked to run for a date the configured calendar declares a
 * non-business date (gap <b>T3-4</b>). Raised BEFORE anything is persisted, so a blocked window leaves no
 * partial batch behind and the next window retries cleanly.
 *
 * <p>Distinguished from a genuine failure on purpose: the scheduler records the run as
 * {@code SKIPPED_NON_BUSINESS_DAY} rather than {@code FAILED} and raises no alert — a skipped holiday is
 * the calendar working, not an incident.
 */
public class NonBusinessDayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final LocalDate date;

    public NonBusinessDayException(LocalDate date, String what) {
        super(what + " blocked: " + date + " is a configured non-business date "
                + "(gmepay.calendar.non-business-dates / non-business-days-of-week)");
        this.date = date;
    }

    public LocalDate getDate() {
        return date;
    }
}
