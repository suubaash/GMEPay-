package com.gme.pay.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void krwRoundsToZeroDecimals() {
        Money m = Money.of("10500.6", "KRW").rounded();
        assertEquals(0, m.amount().scale());
        assertEquals(new BigDecimal("10501"), m.amount());
    }

    @Test
    void usdRoundsToTwoDecimals() {
        Money m = Money.of("10.20408", "USD").rounded();
        assertEquals(2, m.amount().scale());
        assertEquals(new BigDecimal("10.20"), m.amount());
    }

    @Test
    void unknownCurrencyDefaultsToTwoDecimals() {
        assertEquals(2, CurrencyScale.scale("MNT"));
    }
}
