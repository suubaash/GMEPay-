package com.gme.pay.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Books a precise (full-precision) amount as a partner settlement liability using that partner's
 * configured rounding rule, and reports the residual. The partner is booked under THEIR rule so
 * partner reconciliation is exact; the residual is posted to the rounding ledger by revenue-ledger.
 */
public final class SettlementRounding {

    private SettlementRounding() {
    }

    /** Book {@code precise} to {@code scale} decimals using {@code mode}; residual = precise - booked. */
    public static BookedAmount book(BigDecimal precise, int scale, RoundingMode mode) {
        Objects.requireNonNull(precise, "precise required");
        Objects.requireNonNull(mode, "rounding mode required");
        BigDecimal booked = precise.setScale(scale, mode);
        BigDecimal residual = precise.subtract(booked);
        return new BookedAmount(booked, residual, scale, mode);
    }

    /** Book using the currency's natural scale and the given rounding mode. */
    public static BookedAmount book(BigDecimal precise, String currency, RoundingMode mode) {
        return book(precise, CurrencyScale.scale(currency), mode);
    }
}
