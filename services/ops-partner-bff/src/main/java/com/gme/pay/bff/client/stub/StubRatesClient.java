package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.RatesClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

/**
 * Phase-1 in-memory stub of {@link RatesClient}. Returns a deterministic
 * preview that respects the 5-step USD-pivot math (with sample treasury rates
 * and a fixed 1% collection + 1% payout margin) so the Admin UI Rates Preview
 * page can render realistic numbers without booting {@code rate-fx}.
 *
 * <p>Formula (matches the rate engine, abbreviated):
 * <pre>
 *   collectionUsd        = amount / treasuryRate(fromCcy -&gt; USD)
 *   collectionMarginUsd  = collectionUsd * collectionMarginBps / 10_000
 *   payoutUsdCost        = collectionUsd - collectionMarginUsd
 *   payoutMarginUsd      = payoutUsdCost * payoutMarginBps / 10_000
 *   payoutAmount         = (payoutUsdCost - payoutMarginUsd) * treasuryRate(USD -&gt; toCcy)
 *   offerRateColl        = amount / collectionUsd        // partner-facing rate on collection
 *   crossRate            = payoutAmount / amount         // fromCcy -&gt; toCcy effective rate
 * </pre>
 *
 * <p>USD-pool math runs at {@code MathContext(20)}; output amounts are rounded
 * to the per-currency scale at the end (KRW/JPY/VND → 0dp, default → 2dp).
 *
 * <p>Short-circuit: when {@code fromCcy.equals(toCcy)}, the engine skips the
 * USD pivot and echoes {@code amount} unchanged on both sides with zero
 * margins.
 */
@Component
public class StubRatesClient implements RatesClient {

    /** Full-precision pool math context (matches the rate engine). */
    private static final MathContext POOL_MC = new MathContext(20);

    /** 1% margin on each leg (100 basis points). */
    private static final BigDecimal MARGIN_BPS = new BigDecimal("100");
    private static final BigDecimal BPS_DENOMINATOR = new BigDecimal("10000");

    /**
     * Sample USD-per-foreign-unit rates. Real engine pulls these from
     * {@code config-registry.treasury_rates}; here they are fixed sample data.
     * Read as: 1 unit of currency = N USD.
     */
    private static final Map<String, BigDecimal> USD_PER_UNIT = Map.of(
            "USD", new BigDecimal("1"),
            "KRW", new BigDecimal("0.00073"),    // 1 KRW = 0.00073 USD (≈ 1370 KRW/USD)
            "JPY", new BigDecimal("0.0064"),     // 1 JPY = 0.0064 USD  (≈ 156 JPY/USD)
            "EUR", new BigDecimal("1.08"),
            "SGD", new BigDecimal("0.74"),
            "INR", new BigDecimal("0.012"),      // 1 INR = 0.012 USD   (≈ 83 INR/USD)
            "VND", new BigDecimal("0.000040"),   // 1 VND = 0.00004 USD
            "MNT", new BigDecimal("0.00029")
    );

    @Override
    public RateQuotePreview previewQuote(RateQuoteRequest req) {
        String fromCcy = req == null || req.fromCcy() == null ? "USD" : req.fromCcy();
        String toCcy   = req == null || req.toCcy()   == null ? "USD" : req.toCcy();
        BigDecimal amount = req == null || req.amount() == null ? BigDecimal.ZERO : req.amount();
        Instant now = Instant.parse("2026-06-10T00:00:00Z");

        // Short-circuit: same-currency quote skips USD pivot, zero margins.
        if (fromCcy.equals(toCcy)) {
            BigDecimal rounded = roundToScale(amount, fromCcy);
            return new RateQuotePreview(
                    rounded, fromCcy,
                    rounded, toCcy,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ONE,  BigDecimal.ONE,
                    true,
                    now);
        }

        BigDecimal fromUsd = USD_PER_UNIT.getOrDefault(fromCcy, BigDecimal.ONE);
        BigDecimal toUsd   = USD_PER_UNIT.getOrDefault(toCcy,   BigDecimal.ONE);

        // Step 1: convert collection amount to USD at full precision.
        BigDecimal collectionUsd = amount.multiply(fromUsd, POOL_MC);

        // Step 2: apply collection-side partner margin.
        BigDecimal collectionMarginUsd = collectionUsd
                .multiply(MARGIN_BPS, POOL_MC)
                .divide(BPS_DENOMINATOR, POOL_MC);

        // Step 3: residual USD pool entry becomes the payout USD cost.
        BigDecimal payoutUsdCost = collectionUsd.subtract(collectionMarginUsd, POOL_MC);

        // Step 4: apply payout-side partner margin.
        BigDecimal payoutMarginUsd = payoutUsdCost
                .multiply(MARGIN_BPS, POOL_MC)
                .divide(BPS_DENOMINATOR, POOL_MC);

        // Step 5: convert remaining USD to target currency.
        BigDecimal payoutUsdNet = payoutUsdCost.subtract(payoutMarginUsd, POOL_MC);
        BigDecimal payoutPrecise = payoutUsdNet.divide(toUsd, POOL_MC);
        BigDecimal payoutAmount = roundToScale(payoutPrecise, toCcy);

        // Display rates.
        BigDecimal offerRateColl = collectionUsd.signum() == 0
                ? BigDecimal.ZERO
                : amount.divide(collectionUsd, POOL_MC);
        BigDecimal crossRate = amount.signum() == 0
                ? BigDecimal.ZERO
                : payoutPrecise.divide(amount, POOL_MC);

        return new RateQuotePreview(
                roundToScale(amount, fromCcy), fromCcy,
                payoutAmount, toCcy,
                collectionUsd.setScale(8, RoundingMode.HALF_UP),
                payoutUsdCost.setScale(8, RoundingMode.HALF_UP),
                collectionMarginUsd.setScale(8, RoundingMode.HALF_UP),
                payoutMarginUsd.setScale(8, RoundingMode.HALF_UP),
                offerRateColl.setScale(8, RoundingMode.HALF_UP),
                crossRate.setScale(8, RoundingMode.HALF_UP),
                false,
                now);
    }

    /** Per-currency display scale (mirrors {@code lib-money/CurrencyScale}). */
    private static BigDecimal roundToScale(BigDecimal value, String ccy) {
        int scale = switch (ccy == null ? "" : ccy) {
            case "KRW", "JPY", "VND" -> 0;
            default -> 2;
        };
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
}
