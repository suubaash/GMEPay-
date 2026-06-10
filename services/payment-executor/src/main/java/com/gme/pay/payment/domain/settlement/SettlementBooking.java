package com.gme.pay.payment.domain.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Result of booking a partner settlement liability under that partner's configured
 * rounding rule (see {@code docs/MONEY_CONVENTION.md}).
 *
 * <ul>
 *   <li>{@code booked}   – the partner-facing liability recorded under their rule.</li>
 *   <li>{@code residual} – {@code precise - booked}; posted to {@code REVENUE_ROUNDING}
 *       by revenue-ledger (positive = rounding gain, negative = rounding loss).</li>
 *   <li>{@code mode}     – the {@link RoundingMode} actually applied.</li>
 *   <li>{@code currency} – the ISO-4217 currency of {@code booked} and {@code residual}.</li>
 * </ul>
 */
public record SettlementBooking(BigDecimal booked, BigDecimal residual, RoundingMode mode, String currency) {
}
