package com.gme.pay.ratefx.partnerb;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Default, in-process {@link PartnerBQuotePort}: reads the most recent {@code source = 'PARTNER'} row
 * for the settlement currency from {@code rate_snapshots}. This lets the PARTNER-source path (WBS 4.6)
 * run end-to-end without a live Partner B endpoint — operations seed PARTNER rows (manually, or a
 * future Partner B feed upserts them) and the resolver reads them like any other snapshot.
 *
 * <p>This is the only {@code PartnerBQuotePort} bean today; a real HTTP-backed implementation can be
 * introduced later as a {@code @Primary} bean (or behind a profile/property) so it takes precedence
 * without touching callers.
 *
 * <p>Per the port contract, a missing PARTNER row or a non-positive rate is
 * {@link ErrorCode#PARTNER_B_QUOTE_UNAVAILABLE} — never a silent fallback to a LIVE/MANUAL rate.
 */
@Component
public class SnapshotPartnerBQuotePort implements PartnerBQuotePort {

    /** Snapshot source discriminator for Partner-B-quoted rates. */
    public static final String SOURCE = "PARTNER";

    private final RateSnapshotRepository snapshots;
    private final Clock clock;

    public SnapshotPartnerBQuotePort(RateSnapshotRepository snapshots, Clock clock) {
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Override
    public PartnerBQuote fetchQuote(String schemeId, String settlementCcy) {
        if (settlementCcy == null || settlementCcy.isBlank()) {
            throw new ApiException(ErrorCode.PARTNER_B_QUOTE_UNAVAILABLE,
                    "Partner B quote requires a settlement currency");
        }
        String ccy = settlementCcy.toUpperCase(Locale.ROOT);
        RateSnapshotEntity row = snapshots
                .findFirstByCurrencyCodeAndSourceAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
                        ccy, SOURCE, clock.instant())
                .orElseThrow(() -> new ApiException(ErrorCode.PARTNER_B_QUOTE_UNAVAILABLE,
                        "no PARTNER quote available for " + ccy
                                + " (scheme=" + schemeId + ")"));
        BigDecimal rate = row.getUsdRate();
        if (rate == null || rate.signum() <= 0) {
            throw new ApiException(ErrorCode.PARTNER_B_QUOTE_UNAVAILABLE,
                    "PARTNER quote for " + ccy + " is non-positive");
        }
        return new PartnerBQuote(rate, row.getSnapshotId(), null);
    }
}
