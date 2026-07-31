package com.gme.pay.payment.domain.client;

import com.gme.pay.contracts.SchemeOperatingHoursView;

import java.util.List;

/**
 * Port: the scheme's weekly operating schedule ({@code scheme_operating_hours}, V024) that the
 * operating-hours gate evaluates. Gap <b>T3-6</b>.
 *
 * <p>Deliberately a DUMB read — it returns rows, not a verdict. The verdict is computed by the pure
 * {@code SchemeAvailability.evaluate} in lib-api-contracts, so:
 * <ul>
 *   <li>the timezone arithmetic exists once and is unit-tested without a network or a clock stub;</li>
 *   <li>a config-registry outage degrades to an EMPTY row list, which evaluates to
 *       {@code UNVERIFIED} — an observable "window unknown", never a wrong "open" and never a
 *       blocked corridor.</li>
 * </ul>
 *
 * <p>Implementations MUST NOT throw on an unreachable upstream or an unknown scheme: returning an
 * empty list is the correct, three-state-preserving answer.
 *
 * @see com.gme.pay.payment.domain.SchemeOperatingHoursGate
 */
public interface SchemeOperatingHoursClient {

    /**
     * The scheme's rows, Monday(0)..Sunday(6) — or an EMPTY list when the scheme is unknown, has no
     * seeded schedule, or the schedule could not be read.
     *
     * @param schemeId a scheme code (any case; implementations canonicalise)
     */
    List<SchemeOperatingHoursView> weeklySchedule(String schemeId);
}
