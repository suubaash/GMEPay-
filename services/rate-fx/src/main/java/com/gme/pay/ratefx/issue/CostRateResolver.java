package com.gme.pay.ratefx.issue;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves a treasury COST RATE (units of a currency per 1 USD, RATE-04 §3.2) from the rate-fx
 * snapshot store for the quote issuer. The reference source is configurable
 * ({@code gmepay.rate-fx.cost-rate-source}, default {@code LIVE} — the source the XE scheduler
 * upserts).
 *
 * <p>A USD leg is an IDENTITY leg: returns {@code null}, which {@link com.gme.pay.ratefx.RateEngine}
 * treats as rate 1.0. A non-USD currency with no current snapshot is a hard error — the engine
 * cannot price a cross-border leg without a rate, so we fail the quote rather than guess.
 */
@Component
public class CostRateResolver {

    private static final String USD = "USD";

    private final RateSnapshotRepository snapshots;
    private final Clock clock;
    private final String source;

    public CostRateResolver(RateSnapshotRepository snapshots, Clock clock,
                            @Value("${gmepay.rate-fx.cost-rate-source:LIVE}") String source) {
        this.snapshots = snapshots;
        this.clock = clock;
        this.source = source;
    }

    /**
     * Cost rate for {@code currency}: {@code null} for a USD (identity) leg; otherwise the latest
     * effective snapshot's USD rate. Throws {@link ApiException} when a non-USD currency has no
     * current snapshot.
     */
    public BigDecimal resolve(String currency) {
        if (currency == null || USD.equalsIgnoreCase(currency)) {
            return null; // IDENTITY leg — engine forces 1.0
        }
        String ccy = currency.toUpperCase(Locale.ROOT);
        return snapshots
                .findFirstByCurrencyCodeAndSourceAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
                        ccy, source, clock.instant())
                .map(RateSnapshotEntity::getUsdRate)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "no " + source + " reference rate snapshot for " + ccy
                                + " — cannot price this leg"));
    }
}
