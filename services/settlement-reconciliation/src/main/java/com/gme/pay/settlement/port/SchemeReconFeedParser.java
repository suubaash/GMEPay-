package com.gme.pay.settlement.port;

import com.gme.pay.settlement.corridor.SchemeSettlementRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * Plug-in point for a REAL partner settlement / reconciliation FILE.
 *
 * <p><b>FORMAT PENDING — external gates O4 (SendMN) and T4-7/O5–O6 (9Pay).</b> No implementation
 * parses a partner file today, and none is invented here: the SendMN API documentation is silent on
 * its recon file format (plan open issue O4), and 9Pay has no hub payout orchestration at all yet
 * (register item T4-7, awaiting a product decision), so there are no hub-side 9Pay payouts for a
 * partner file to be reconciled against.
 *
 * <p>What exists instead is the internal three-way tie-out
 * ({@link com.gme.pay.settlement.corridor.CorridorThreeWayReconciler}) over the three sources GME
 * itself owns: transaction records, prefunding movements, and the scheme adapter's record of what the
 * scheme confirmed. That needs no partner file.
 *
 * <p>When a format does arrive, an implementation of this interface becomes a FOURTH leg without
 * disturbing the internal tie-out:
 * <ol>
 *   <li>parse the file into {@link SchemeSettlementRecord} (the same type the adapter leg produces —
 *       so the matcher is reused, not forked);</li>
 *   <li>register the bean; the reconciler picks it up when {@link #available()} turns true;</li>
 *   <li>partner-vs-adapter differences then surface as ordinary recon exceptions
 *       ({@code MISSING_SCHEME} / {@code DISCREPANCY}) in the same ops queue.</li>
 * </ol>
 * Note the ZeroPay lane already does exactly this with {@code ZP0062Parser}/{@code ZP0064Parser};
 * this interface is the cross-border equivalent of those parsers, intentionally left unimplemented
 * rather than guessed at.
 */
public interface SchemeReconFeedParser {

    /** Scheme this parser speaks for, upper-case (e.g. {@code SENDMN}). */
    String scheme();

    /**
     * {@code false} while the partner's file format is unknown. The reconciler must consult this
     * before calling {@link #parse}, and records the answer on the daily summary
     * ({@code scheme_feed_available}) so a reader can never mistake an internal-only tie-out for a
     * partner-confirmed one.
     */
    boolean available();

    /**
     * Parse one partner settlement/recon file for {@code settlementDate}.
     *
     * @throws IllegalStateException when {@link #available()} is {@code false} — the format is not
     *                              defined, so any parse would be fabricated
     */
    List<SchemeSettlementRecord> parse(LocalDate settlementDate, List<String> lines);
}
