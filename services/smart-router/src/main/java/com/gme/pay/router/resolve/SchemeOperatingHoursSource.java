package com.gme.pay.router.resolve;

import com.gme.pay.contracts.SchemeOperatingHoursView;
import java.util.List;

/**
 * Port: the scheme's weekly operating schedule ({@code scheme_operating_hours}, V024) that
 * {@link LocationSchemeResolver} consults to answer V024's own stated question — "can this transaction
 * route NOW?". Gap <b>T3-6</b>.
 *
 * <p>Same shape as {@link PartnerSchemeRegistry}: rows in, policy decided by the resolver. The port
 * returns raw rows rather than a verdict so the timezone arithmetic lives in exactly one place
 * (the pure {@code SchemeAvailability.evaluate} in lib-api-contracts) and is shared with
 * payment-executor's gate.
 *
 * <p><b>Contract:</b> never {@code null}, never throws. An unknown scheme, an unseeded schedule and an
 * unreachable config-registry all return an EMPTY list, which the resolver reads as
 * {@code UNVERIFIED} — routing proceeds and says so, rather than a corridor being taken down by
 * missing or unreadable reference data.
 */
public interface SchemeOperatingHoursSource {

    /**
     * The scheme's rows, Monday(0)..Sunday(6); empty when unknown / unseeded / unreadable.
     *
     * @param schemeId a V022 roster scheme id (e.g. {@code ZEROPAY}, {@code NAPAS_247}).
     */
    List<SchemeOperatingHoursView> weeklySchedule(String schemeId);
}
