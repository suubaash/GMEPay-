package com.gme.pay.ratefx.partnerb;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A per-transaction cost rate quoted by Partner B (the authoritative settlement counterparty for a
 * PARTNER-source leg, RATE-04 §7.3). Unlike LIVE/MANUAL treasury rates — which rate-fx owns — a
 * PARTNER rate is supplied externally at quote time and re-asserted at commit time.
 *
 * <p>Convention: {@code rate} = units of the settlement currency per 1 USD (same convention as the
 * treasury snapshot {@code usd_rate}). {@code quoteReference} is Partner B's opaque audit handle for
 * the quote, carried through to the locked rate quote. {@code validUntil} is Partner B's own
 * expiry — informational; rate-fx's TTL lock is authoritative for GME.
 *
 * @param rate           units of settlement ccy per 1 USD; must be {@code > 0}
 * @param quoteReference Partner B's opaque reference for this quote (nullable but recommended)
 * @param validUntil     Partner B's stated expiry instant (nullable)
 */
public record PartnerBQuote(BigDecimal rate, String quoteReference, Instant validUntil) {
}
