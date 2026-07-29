package com.gme.pay.settlement.port;

import com.gme.pay.settlement.corridor.SchemeSettlementRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * Anti-corruption port for leg (c) of the cross-border three-way tie-out: the scheme adapter's own
 * record of what the scheme confirmed on a settlement date, including the scheme-registered rate and
 * the resulting settlement amount owed.
 *
 * <p>Implementations read a GME-owned adapter over HTTP (e.g.
 * {@code GET /internal/scheme/sendmn/settlement/daily}). They do NOT read a partner statement —
 * that is {@link SchemeReconFeedParser}'s job, and it is externally gated.
 */
public interface SchemeSettlementPort {

    /** Scheme this port speaks for, upper-case (e.g. {@code SENDMN}). */
    String scheme();

    /**
     * What the adapter recorded the scheme as having confirmed on {@code settlementDate}.
     *
     * @return matching records; empty (never null) when the day had none or the adapter is
     *         unreachable — implementations MUST log loudly on transport failure, because an empty
     *         list is indistinguishable from "the scheme confirmed nothing" and would raise
     *         MISSING_SCHEME breaks.
     */
    List<SchemeSettlementRecord> confirmedOn(LocalDate settlementDate);
}
