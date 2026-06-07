package com.gme.pay.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ISO-4217 minor-unit scale for currencies used by GMEPay+.
 * Rounding is applied ONLY at output points (collection_amount, send_amount, payout);
 * intermediate USD-pool math keeps full precision so the pool identity is not broken.
 */
public final class CurrencyScale {

    private CurrencyScale() {
    }

    /** Number of decimal places for a currency. Defaults to 2. */
    public static int scale(String currency) {
        if (currency == null) {
            return 2;
        }
        switch (currency.toUpperCase()) {
            case "KRW":
            case "JPY":
            case "VND":
                return 0;
            default:
                return 2;
        }
    }

    /** Round a value to its currency's scale using HALF_UP. */
    public static BigDecimal round(BigDecimal value, String currency) {
        return value.setScale(scale(currency), RoundingMode.HALF_UP);
    }
}
