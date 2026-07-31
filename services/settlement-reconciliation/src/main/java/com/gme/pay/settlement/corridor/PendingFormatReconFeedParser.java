package com.gme.pay.settlement.corridor;

import com.gme.pay.settlement.port.SchemeReconFeedParser;

import java.time.LocalDate;
import java.util.List;

/**
 * The honest placeholder for a partner recon feed whose FILE FORMAT is not yet known.
 *
 * <p>Registered for SENDMN (see {@link CorridorReconConfig}) so the reconciler has something to ask
 * {@link #available()} and can stamp {@code scheme_feed_available=false} on the daily summary —
 * making it explicit in the data that the day's tie-out used only GME-owned sources.
 *
 * <p>{@link #parse} throws rather than returning a plausible-looking empty result: a silent empty
 * parse would read as "the partner confirmed nothing", which is exactly the kind of invented fact
 * this class exists to prevent.
 *
 * @param scheme upper-case scheme code
 * @param gate   the external gate blocking the format, quoted in the failure message
 *               (e.g. {@code "O4 — SendMN recon file format"})
 */
public record PendingFormatReconFeedParser(String scheme, String gate) implements SchemeReconFeedParser {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public List<SchemeSettlementRecord> parse(LocalDate settlementDate, List<String> lines) {
        throw new IllegalStateException(
                "no recon file format is defined for scheme " + scheme + " (external gate: " + gate
                        + ") — the internal three-way tie-out runs without one; nothing may be parsed");
    }
}
