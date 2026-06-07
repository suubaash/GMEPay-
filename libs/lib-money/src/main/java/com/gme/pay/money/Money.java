package com.gme.pay.money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An exact monetary amount in a given currency. Always use BigDecimal for money — never double.
 */
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount required");
        Objects.requireNonNull(currency, "currency required");
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    /** Returns a copy rounded to this currency's minor-unit scale. */
    public Money rounded() {
        return new Money(CurrencyScale.round(amount, currency), currency);
    }
}
