package com.gme.pay.contracts;

/**
 * The verdict of evaluating a scheme's {@code scheme_operating_hours} schedule (V024) at an instant —
 * gap <b>T3-6</b>.
 *
 * <p><b>Three states, never two.</b> This mirrors the deliberate design of the settlement
 * {@code BusinessCalendar}'s {@code BusinessDayVerdict} (gap T3-4): the absence of reference data is
 * its OWN answer, not the permissive one. "The table has no row for this scheme on this weekday" is
 * {@link #UNVERIFIED}; it is never silently reported as {@link #OPEN}, because reading missing data as
 * "definitely open" is precisely the assumption T3-6 exists to remove.
 *
 * <p>What a caller DOES with {@link #UNVERIFIED} is the caller's policy, not this enum's:
 * payment-executor and smart-router both <b>proceed</b> on UNVERIFIED (refusing a corridor because
 * nobody has seeded its schedule would be worse than routing to it) while making the fact loudly
 * observable. Only {@link #CLOSED} — an actual row that actually excludes the current local time —
 * rejects a payment.
 */
public enum SchemeAvailabilityVerdict {

    /** A row exists for this scheme + local weekday and the local time falls inside its window. */
    OPEN,

    /** A row exists for this scheme + local weekday and the local time falls OUTSIDE its window. */
    CLOSED,

    /**
     * No usable row exists for this scheme + local weekday (nothing seeded, an unseeded weekday, or a
     * row whose times / timezone are unusable). The window is UNKNOWN — deliberately distinct from
     * {@link #OPEN}.
     */
    UNVERIFIED
}
