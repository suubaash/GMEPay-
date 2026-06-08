package com.gme.pay.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.errors.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RuleTest {

    private Rule rule(String a, String b, String mA, String mB) {
        return new Rule("P1", "ZEROPAY", Direction.INBOUND, a, b,
                new BigDecimal(mA), new BigDecimal(mB), BigDecimal.ZERO);
    }

    @Test
    void crossBorderWithTwoPercentCombinedIsValid() {
        assertDoesNotThrow(() -> rule("MNT", "KRW", "0.01", "0.01").validate());
    }

    @Test
    void crossBorderBelowTwoPercentIsRejected() {
        assertThrows(ApiException.class, () -> rule("MNT", "KRW", "0.005", "0.005").validate());
    }

    @Test
    void sameCurrencyWithZeroMarginIsValid() {
        assertDoesNotThrow(() -> rule("KRW", "KRW", "0", "0").validate());
    }

    @Test
    void sameCurrencyWithNonZeroMarginIsRejected() {
        assertThrows(ApiException.class, () -> rule("KRW", "KRW", "0.01", "0").validate());
    }
}
