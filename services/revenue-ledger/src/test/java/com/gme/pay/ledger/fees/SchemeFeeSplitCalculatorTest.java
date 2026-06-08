package com.gme.pay.ledger.fees;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SchemeFeeSplitCalculator}.
 * All arithmetic uses floor (ROUND_DOWN). Test vectors from WBS 7.2-T10.
 * No Spring context, no Docker, no Testcontainers — pure JUnit 5.
 */
class SchemeFeeSplitCalculatorTest {

    private final SchemeFeeSplitCalculator calculator = new SchemeFeeSplitCalculator();

    private static final BigDecimal RATE_MERCHANT = new BigDecimal("0.0080");
    private static final BigDecimal RATE_VAN      = new BigDecimal("0.0008");
    private static final BigDecimal GME_SHARE     = new BigDecimal("0.70");

    // -----------------------------------------------------------------------
    // Parameterised test vectors A-E (from 7.2-T10 spec)
    // -----------------------------------------------------------------------

    /**
     * Vector A: payout=15000, merchant_fee=0.80%, van=0.08%, gme=70%
     *   gross=120, van=12, net=108, gme=75, zeropay=33
     */
    @Test
    void vectorA_typicalDomesticTransaction() {
        FeeShareResult r = calculator.calculate(15000, new BigDecimal("0.0080"), new BigDecimal("0.0008"), new BigDecimal("0.70"));

        assertEquals(120L, r.grossMerchantFeeKrw(), "gross");
        assertEquals(12L,  r.vanFeeKrw(),           "van");
        assertEquals(108L, r.netMerchantFeeKrw(),   "net");
        assertEquals(75L,  r.gmeFeeShareKrw(),       "gme (floor 108×0.70=75)");
        assertEquals(33L,  r.zeropayFeeShareKrw(),   "zeropay");
        assertInvariant(r);
    }

    /**
     * Vector B: payout=100000, merchant_fee=2.00%, van=0.20%, gme=70%
     *   gross=2000, van=200, net=1800, gme=1260, zeropay=540
     */
    @Test
    void vectorB_highValueTransaction() {
        FeeShareResult r = calculator.calculate(100000, new BigDecimal("0.0200"), new BigDecimal("0.0020"), new BigDecimal("0.70"));

        assertEquals(2000L, r.grossMerchantFeeKrw());
        assertEquals(200L,  r.vanFeeKrw());
        assertEquals(1800L, r.netMerchantFeeKrw());
        assertEquals(1260L, r.gmeFeeShareKrw());
        assertEquals(540L,  r.zeropayFeeShareKrw());
        assertInvariant(r);
    }

    /**
     * Vector C: payout=1 KRW — floor produces all-zero fees (tiny amount below rate threshold)
     */
    @Test
    void vectorC_tinyPayoutProducesZeroFees() {
        FeeShareResult r = calculator.calculate(1, new BigDecimal("0.0080"), new BigDecimal("0.0008"), new BigDecimal("0.70"));

        assertEquals(0L, r.grossMerchantFeeKrw());
        assertEquals(0L, r.vanFeeKrw());
        assertEquals(0L, r.netMerchantFeeKrw());
        assertEquals(0L, r.gmeFeeShareKrw());
        assertEquals(0L, r.zeropayFeeShareKrw());
        assertInvariant(r);
    }

    /**
     * Vector D: payout=999, merchant_fee=0.80%, van=0.08%, gme=70%
     *   gross=7 (floor(999×0.008)=7), van=0 (floor(999×0.0008)=0), net=7, gme=4 (floor(7×0.70)=4), zeropay=3
     * Tests floor behaviour where VAN fee rounds to zero.
     */
    @Test
    void vectorD_floorBehaviourSubUnitVanFee() {
        FeeShareResult r = calculator.calculate(999, new BigDecimal("0.0080"), new BigDecimal("0.0008"), new BigDecimal("0.70"));

        assertEquals(7L, r.grossMerchantFeeKrw(), "gross=floor(999*0.008)=7");
        assertEquals(0L, r.vanFeeKrw(),           "van=floor(999*0.0008)=0");
        assertEquals(7L, r.netMerchantFeeKrw(),   "net=7-0=7");
        assertEquals(4L, r.gmeFeeShareKrw(),       "gme=floor(7*0.70)=4");
        assertEquals(3L, r.zeropayFeeShareKrw(),   "zeropay=7-4=3");
        assertInvariant(r);
    }

    /**
     * Vector E: payout=0 — all zeros, no exception.
     */
    @Test
    void vectorE_zeroPayoutAllZeros() {
        FeeShareResult r = calculator.calculate(0, new BigDecimal("0.0080"), new BigDecimal("0.0008"), new BigDecimal("0.70"));

        assertEquals(0L, r.grossMerchantFeeKrw());
        assertEquals(0L, r.vanFeeKrw());
        assertEquals(0L, r.netMerchantFeeKrw());
        assertEquals(0L, r.gmeFeeShareKrw());
        assertEquals(0L, r.zeropayFeeShareKrw());
        assertInvariant(r);
    }

    // -----------------------------------------------------------------------
    // Guard tests
    // -----------------------------------------------------------------------

    @Test
    void negativePayout_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(-1, RATE_MERCHANT, RATE_VAN, GME_SHARE));
        assertTrue(ex.getMessage().contains("payoutAmountKrw"),
                "message should mention payoutAmountKrw, got: " + ex.getMessage());
    }

    @Test
    void vanFeeRateEqualToMerchantFeeRate_throwsIllegalArgument() {
        BigDecimal same = new BigDecimal("0.0080");
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(10000, same, same, GME_SHARE));
    }

    @Test
    void gmeFeeSharePctAboveOne_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(10000, RATE_MERCHANT, RATE_VAN, new BigDecimal("1.01")));
    }

    @Test
    void gmeFeeSharePctZero_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(10000, RATE_MERCHANT, RATE_VAN, BigDecimal.ZERO));
    }

    @Test
    void negativeMerchantFeeRate_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(10000, new BigDecimal("-0.001"), RATE_VAN, GME_SHARE));
    }

    // -----------------------------------------------------------------------
    // Invariant stress test: gme + zeropay == net for 10,000 random payouts
    // -----------------------------------------------------------------------

    @Test
    void invariantHoldsForTenThousandRandomPayouts() {
        Random rng = new Random(42L); // deterministic seed
        BigDecimal merchantRate = new BigDecimal("0.0080");
        BigDecimal vanRate      = new BigDecimal("0.0008");
        BigDecimal gmeShare     = new BigDecimal("0.70");

        for (int i = 0; i < 10_000; i++) {
            long payout = rng.nextLong(1_000_001); // 0..1_000_000
            FeeShareResult r = calculator.calculate(payout, merchantRate, vanRate, gmeShare);
            assertInvariant(r);
        }
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static void assertInvariant(FeeShareResult r) {
        assertEquals(r.netMerchantFeeKrw(), r.gmeFeeShareKrw() + r.zeropayFeeShareKrw(),
                "Invariant violated: gme + zeropay != net");
    }
}
