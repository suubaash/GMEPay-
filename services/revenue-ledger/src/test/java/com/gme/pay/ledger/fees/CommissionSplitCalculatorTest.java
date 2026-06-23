package com.gme.pay.ledger.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link CommissionSplitCalculator} — the two-sided commission
 * split driven by the configurable shares (V031). Pure JUnit 5, no Spring.
 *
 * <p>Pins the worked example, both split invariants under fuzzing, the partner
 * boundary shares (0 and 1), and the {@code partnerSharePct} guard.
 */
class CommissionSplitCalculatorTest {

    private final CommissionSplitCalculator calc =
            new CommissionSplitCalculator(new SchemeFeeSplitCalculator());

    @Test
    void workedExample_twoSidedSplit() {
        CommissionSplit s = calc.calculate(100_000,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));

        assertEquals(2000L, s.grossMerchantFeeKrw(), "gross");
        assertEquals(200L, s.vanFeeKrw(), "van");
        assertEquals(1800L, s.netMerchantFeeKrw(), "net");
        assertEquals(1260L, s.gmeGrossShareKrw(), "gmeGross = floor(1800×0.70)");
        assertEquals(540L, s.schemeShareKrw(), "scheme = remainder");
        assertEquals(378L, s.partnerShareKrw(), "partner = floor(1260×0.30)");
        assertEquals(882L, s.gmeNetShareKrw(), "gmeNet = remainder");
        assertInvariants(s);
    }

    /**
     * Vectors: payout, merchantRate, vanRate, gmeShare, partnerShare,
     *          expGmeGross, expScheme, expPartner, expGmeNet.
     */
    @ParameterizedTest
    @CsvSource({
            // typical domestic: gross=120 van=12 net=108 gmeGross=75 scheme=33
            "15000, 0.0080, 0.0008, 0.70, 0.30, 75, 33, 22, 53",
            // partner gets nothing → GME keeps all of its gross share
            "15000, 0.0080, 0.0008, 0.70, 0.00, 75, 33, 0, 75",
            // partner gets everything → GME keeps nothing
            "15000, 0.0080, 0.0008, 0.70, 1.00, 75, 33, 75, 0",
            // non-ZeroPay scheme share (gme 60%): net=108 gmeGross=64 scheme=44; partner 50%→32/32
            "15000, 0.0080, 0.0008, 0.60, 0.50, 64, 44, 32, 32",
    })
    void vectors(long payout, String mr, String van, String gme, String partner,
                 long expGmeGross, long expScheme, long expPartner, long expGmeNet) {
        CommissionSplit s = calc.calculate(payout,
                new BigDecimal(mr), new BigDecimal(van), new BigDecimal(gme), new BigDecimal(partner));
        assertEquals(expGmeGross, s.gmeGrossShareKrw(), "gmeGross");
        assertEquals(expScheme, s.schemeShareKrw(), "scheme");
        assertEquals(expPartner, s.partnerShareKrw(), "partner");
        assertEquals(expGmeNet, s.gmeNetShareKrw(), "gmeNet");
        assertInvariants(s);
    }

    @Test
    void invariantsHoldUnderFuzzing() {
        Random rnd = new Random(42); // fixed seed — Math.random/Date are banned anyway
        for (int i = 0; i < 5000; i++) {
            long payout = rnd.nextInt(2_000_000);
            // merchant in [0.0010, 0.0220], van strictly less, both scale 4
            BigDecimal merchant = new BigDecimal(10 + rnd.nextInt(211)).movePointLeft(4);
            BigDecimal van = new BigDecimal(rnd.nextInt(9)).movePointLeft(4); // 0.0000..0.0008
            BigDecimal gme = new BigDecimal(1 + rnd.nextInt(100)).movePointLeft(2); // 0.01..1.00
            BigDecimal partner = new BigDecimal(rnd.nextInt(101)).movePointLeft(2); // 0.00..1.00

            CommissionSplit s = calc.calculate(payout, merchant, van, gme, partner);
            assertInvariants(s);
            assertTrue(s.partnerShareKrw() >= 0 && s.gmeNetShareKrw() >= 0
                    && s.schemeShareKrw() >= 0 && s.gmeGrossShareKrw() >= 0,
                    "no negative shares");
        }
    }

    @Test
    void zeroNetFee_yieldsAllZeroShares() {
        // payout too small to round any fee up to 1 KRW
        CommissionSplit s = calc.calculate(10,
                new BigDecimal("0.0080"), new BigDecimal("0.0008"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));
        assertEquals(0L, s.gmeGrossShareKrw());
        assertEquals(0L, s.partnerShareKrw());
        assertEquals(0L, s.gmeNetShareKrw());
        assertInvariants(s);
    }

    @Test
    void merchantZeroWithPositiveVan_throws_noNegativeShares() {
        // Regression: the old guard let merchant=0 + van>0 through, driving net (and
        // every share) negative. It must now be rejected as bad config.
        assertThrows(IllegalArgumentException.class, () -> calc.calculate(100_000,
                new BigDecimal("0.0000"), new BigDecimal("0.0008"),
                new BigDecimal("0.70"), new BigDecimal("0.30")));
    }

    @Test
    void merchantRateAboveOne_throws() {
        assertThrows(IllegalArgumentException.class, () -> calc.calculate(100_000,
                new BigDecimal("2.0"), new BigDecimal("0.0008"),
                new BigDecimal("0.70"), new BigDecimal("0.30")));
    }

    @Test
    void zeroFeeTransaction_bothRatesZero_isAllowed() {
        CommissionSplit s = calc.calculate(100_000,
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.70"), new BigDecimal("0.30"));
        assertEquals(0L, s.netMerchantFeeKrw());
        assertInvariants(s);
    }

    @Test
    void partnerShareGuard_rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> calc.calculate(15000,
                new BigDecimal("0.0080"), new BigDecimal("0.0008"),
                new BigDecimal("0.70"), new BigDecimal("1.01")));
        assertThrows(IllegalArgumentException.class, () -> calc.calculate(15000,
                new BigDecimal("0.0080"), new BigDecimal("0.0008"),
                new BigDecimal("0.70"), new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> calc.calculate(15000,
                new BigDecimal("0.0080"), new BigDecimal("0.0008"),
                new BigDecimal("0.70"), null));
    }

    private static void assertInvariants(CommissionSplit s) {
        assertEquals(s.netMerchantFeeKrw(), s.gmeGrossShareKrw() + s.schemeShareKrw(),
                "split 1: gmeGross + scheme == net");
        assertEquals(s.gmeGrossShareKrw(), s.partnerShareKrw() + s.gmeNetShareKrw(),
                "split 2: partner + gmeNet == gmeGross");
        assertEquals(s.grossMerchantFeeKrw() - s.vanFeeKrw(), s.netMerchantFeeKrw(),
                "net == gross - van");
    }
}
