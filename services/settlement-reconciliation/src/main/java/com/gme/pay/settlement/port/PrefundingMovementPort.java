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
     * USD deductions recorded against {@code partnerCode}'s float on {@code settlementDate}.
     *
     * @param partnerCode    prefunding partner code (e.g. {@code SENDMN})
     * @param settlementDate business date being reconciled
     * @return matching movements; empty (never null) when there were none or the source is
     *         unreachable. An empty list from an unreachable source would show up as
     *         MISSING_PREFUNDING breaks, so implementations MUST log loudly on transport failure.
     */
    List<PrefundingMovement> deductionsOn(String partnerCode, LocalDate settlementDate);
}
