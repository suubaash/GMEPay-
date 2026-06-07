package com.gme.pay.ratefx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RateEngineTest {

    private final RateEngine engine = new RateEngine();

    /**
     * Cross-border inbound: payout KRW, settle B KRW (usd_KRW=1350), settle A / collection MNT
     * (usd_MNT=3500), m_a=m_b=1% (2% combined), service charge 500 MNT.
     * target_payout 13500 KRW -> payout_usd_cost exactly 10 USD.
     */
    @Test
    void crossBorderInbound_mntToKrw_viaUsdPool() {
        RateInput in = new RateInput(
                new BigDecimal("13500"), "MNT", "MNT", "KRW", "KRW",
                new BigDecimal("3500"), new BigDecimal("1350"),
                new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("500"));

        RateResult r = engine.quote(in);

        assertFalse(r.shortCircuit());
        assertEquals(10.0, r.payoutUsdCost().doubleValue(), 0.0001);
        assertEquals(10.20408, r.collectionUsd().doubleValue(), 0.0001);
        // pool identity holds within 0.01 USD
        double delta = r.collectionUsd().subtract(r.collectionMarginUsd())
                .subtract(r.payoutMarginUsd()).subtract(r.payoutUsdCost()).abs().doubleValue();
        assertTrue(delta < 0.01, "pool identity delta " + delta);
        // outputs rounded to MNT (2dp)
        assertEquals(0, r.sendAmount().compareTo(new BigDecimal("35714.29")), "send_amount");
        assertEquals(0, r.collectionAmount().compareTo(new BigDecimal("36214.29")), "collection_amount");
        // offer_rate_coll = cost_rate_coll / (1 - m_a) = 3500 / 0.99
        assertEquals(3535.35, r.offerRateColl().doubleValue(), 0.01);
        assertEquals(0.378, r.crossRate().doubleValue(), 0.001);
    }

    /** Same-currency short-circuit: all KRW -> no USD pool, collection = payout + service charge. */
    @Test
    void sameCurrencyShortCircuit_skipsUsdPool() {
        RateInput in = new RateInput(
                new BigDecimal("10000"), "KRW", "KRW", "KRW", "KRW",
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500"));

        RateResult r = engine.quote(in);

        assertTrue(r.shortCircuit());
        assertEquals(0, r.collectionAmount().compareTo(new BigDecimal("10500")));
        assertEquals(0, r.collectionMarginUsd().compareTo(BigDecimal.ZERO));
    }

    /** Combined margin below 2% for a cross-border rule is rejected. */
    @Test
    void belowMinimumCombinedMargin_isRejected() {
        RateInput in = new RateInput(
                new BigDecimal("13500"), "MNT", "MNT", "KRW", "KRW",
                new BigDecimal("3500"), new BigDecimal("1350"),
                new BigDecimal("0.005"), new BigDecimal("0.005"), new BigDecimal("500"));

        assertThrows(IllegalArgumentException.class, () -> engine.quote(in));
    }

    /** Identity leg: settle A = USD forces cost_rate_coll = 1.0. */
    @Test
    void identityLeg_usdSettlementAForcesRateOne() {
        RateInput in = new RateInput(
                new BigDecimal("13500"), "USD", "USD", "KRW", "KRW",
                null, new BigDecimal("1350"),
                new BigDecimal("0.01"), new BigDecimal("0.01"), BigDecimal.ZERO);

        RateResult r = engine.quote(in);
        // send_amount == collection_usd (rate 1.0), rounded to USD 2dp
        assertEquals(r.collectionUsd().doubleValue(), r.sendAmount().doubleValue(), 0.01);
    }
}
