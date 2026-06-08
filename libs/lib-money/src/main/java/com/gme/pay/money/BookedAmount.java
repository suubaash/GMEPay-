package com.gme.pay.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Result of booking a precise amount under a rounding policy.
 * {@code booked} is what we record as the liability with the partner; {@code residual = precise - booked}
 * is the rounding gain (positive) or loss (negative) to post to the rounding ledger.
 */
public record BookedAmount(BigDecimal booked, BigDecimal residual, int scale, RoundingMode mode) {
}
