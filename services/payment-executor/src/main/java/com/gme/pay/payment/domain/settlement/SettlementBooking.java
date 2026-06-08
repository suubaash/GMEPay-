package com.gme.pay.payment.domain.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Result of booking a partner settlement liability: the {@code booked} amount (recorded with the
 * partner under their rounding rule) and the {@code residual} (posted to REVENUE_ROUNDING).
 */
public record SettlementBooking(BigDecimal booked, BigDecimal residual, RoundingMode mode, String currency) {
}
