package com.gme.pay.settlement.port;

import com.gme.pay.settlement.corridor.PrefundingMovement;

import java.time.LocalDate;
import java.util.List;

/**
 * Anti-corruption port for leg (b) of the cross-border three-way tie-out: the USD movements the hub
 * made against a partner's prefunding float on a settlement date.
 *
 * <p>Read-only by construction — this service never moves float; it only checks that the float
 * movement, the transaction record and the scheme's confirmation tell the same story.
 */
public interface PrefundingMovementPort {

    /**
     * <b>All</b> USD float movements recorded against {@code partnerCode} on {@code settlementDate} —
     * deductions <em>and</em> credits back (reversals), each signed per
     * {@link PrefundingMovement#amountUsd()}.
     *
     * <p>Implementations MUST return the <b>complete</b> set for the date. Since T2-8 prefunding
     * publishes a date-ranged, paged movement query with a total count, so a partial read is
     * detectable and must be either completed or reported — never quietly returned as if it were the
     * whole day. That matters because a movement missing from this leg becomes a MISSING_PREFUNDING
     * break, i.e. a fabricated finance exception.
     *
     * @param partnerCode    prefunding partner code (e.g. {@code SENDMN})
     * @param settlementDate business date being reconciled, in the corridor's settlement timezone
     * @return matching movements; empty (never null) when there were none or the source is
     *         unreachable. An empty list from an unreachable source would show up as
     *         MISSING_PREFUNDING breaks, so implementations MUST log loudly on transport failure.
     */
    List<PrefundingMovement> deductionsOn(String partnerCode, LocalDate settlementDate);
}
