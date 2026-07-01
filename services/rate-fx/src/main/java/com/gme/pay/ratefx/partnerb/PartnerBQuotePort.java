package com.gme.pay.ratefx.partnerb;

/**
 * Read port for Partner B's per-transaction quote API (RATE-04 §7.3, WBS 4.6). A PARTNER-source leg
 * does not resolve its cost rate from rate-fx's own treasury snapshots; instead it asks Partner B for
 * a live quote at <em>both</em> {@code /rates} (quote issuance) time and {@code POST /payments}
 * (commit) time. rate-fx consumes Partner B only through this port.
 *
 * <p>Implementations must be config-gated and must NOT require a live external endpoint in tests:
 * {@link SnapshotPartnerBQuotePort} is the default in-process fallback (reads the PARTNER-source rows
 * already persisted in {@code rate_snapshots}); a real HTTP client can be wired later behind the same
 * port without touching callers.
 *
 * <p>Error contract: any failure to obtain a usable quote (endpoint unreachable, null/blank response,
 * non-positive rate) is surfaced as
 * {@link com.gme.pay.errors.ApiException} with {@link com.gme.pay.errors.ErrorCode#PARTNER_B_QUOTE_UNAVAILABLE}
 * — there is NO fallback to a treasury rate (a PARTNER leg priced off a GME-owned rate would
 * mis-state the settlement liability).
 */
public interface PartnerBQuotePort {

    /**
     * Fetch Partner B's current quote for a settlement leg.
     *
     * @param schemeId         the scheme / corridor the quote is for (selects Partner B contract); may be null
     * @param settlementCcy    the leg's settlement currency (ISO-4217), e.g. {@code KRW}
     * @return a non-null quote with a positive rate
     * @throws com.gme.pay.errors.ApiException PARTNER_B_QUOTE_UNAVAILABLE when no usable quote can be obtained
     */
    PartnerBQuote fetchQuote(String schemeId, String settlementCcy);
}
