package com.gme.sim.rateprovider.rates;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory mid-rate store seeded with USD-base rates.
 * A scheduled job applies a bounded ±0.3 % random walk every 60 s.
 */
@Component
public class RateStore {

    /** Supported quote currencies (USD-base mid-rates). */
    static final Map<String, BigDecimal> SEED_RATES;

    static {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("KRW", new BigDecimal("1380.000000"));
        m.put("MNT", new BigDecimal("3450.000000"));
        m.put("KHR", new BigDecimal("4100.000000"));
        m.put("VND", new BigDecimal("25400.000000"));
        m.put("THB", new BigDecimal("36.500000"));
        m.put("SGD", new BigDecimal("1.350000"));
        m.put("CNY", new BigDecimal("7.200000"));
        SEED_RATES = Collections.unmodifiableMap(m);
    }

    /** Current mid-rates (USD = base). Scale 6, HALF_UP. */
    private final ConcurrentHashMap<String, BigDecimal> rates = new ConcurrentHashMap<>();
    private final Random rng = new Random();

    public RateStore() {
        rates.putAll(SEED_RATES);
    }

    /**
     * Returns the mid-rate for the given quote currency (USD base).
     * Returns {@link BigDecimal#ZERO} if the currency is unsupported.
     */
    public BigDecimal usdRate(String quote) {
        return rates.getOrDefault(quote, BigDecimal.ZERO);
    }

    /** All currently supported quote currencies and their USD-base mid-rates. */
    public Map<String, BigDecimal> allRates() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(rates));
    }

    /**
     * Compute cross rate: how many units of {@code quote} per 1 unit of {@code base}.
     * Formula: rate(USD->quote) / rate(USD->base).
     */
    public BigDecimal crossRate(String base, String quote) {
        if ("USD".equals(base)) {
            return usdRate(quote);
        }
        if ("USD".equals(quote)) {
            BigDecimal baseRate = usdRate(base);
            if (baseRate.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
            return BigDecimal.ONE.divide(baseRate, 6, RoundingMode.HALF_UP);
        }
        BigDecimal baseUsd = usdRate(base);
        BigDecimal quoteUsd = usdRate(quote);
        if (baseUsd.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return quoteUsd.divide(baseUsd, 6, RoundingMode.HALF_UP);
    }

    /** @return whether the given currency code is supported (including USD). */
    public boolean supports(String ccy) {
        return "USD".equals(ccy) || rates.containsKey(ccy);
    }

    /**
     * Scheduled random walk: each rate moves by up to ±0.3 % every 60 s.
     * Uses {@link java.util.Random} — this is ordinary runtime code, not a test.
     */
    @Scheduled(fixedDelay = 60_000)
    public void applyRandomWalk() {
        rates.replaceAll((ccy, current) -> {
            // jitter in (-0.003, +0.003)
            double jitter = (rng.nextDouble() * 0.006) - 0.003;
            BigDecimal factor = BigDecimal.ONE.add(
                    new BigDecimal(jitter, new MathContext(6, RoundingMode.HALF_UP)));
            return current.multiply(factor).setScale(6, RoundingMode.HALF_UP);
        });
    }
}
