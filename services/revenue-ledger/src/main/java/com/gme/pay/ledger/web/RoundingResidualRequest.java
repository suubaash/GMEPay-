package com.gme.pay.ledger.web;

import java.math.BigDecimal;

/**
 * Wire payload for {@code POST /v1/journals/rounding-residual}.
 *
 * <p>Per {@code docs/MONEY_CONVENTION.md}, the residual is {@code precise - booked}: positive
 * means the partner was booked under their rule for less than the precise amount (rounding GAIN
 * to GME — REVENUE_ROUNDING credited), negative means the partner was booked for more (rounding
 * LOSS — REVENUE_ROUNDING debited), zero is a no-op (no journal posted).
 *
 * <p>Validation policy (per task spec):
 * <ul>
 *   <li>{@code reference} required (non-null)</li>
 *   <li>{@code currency} required (non-null ISO-4217 code)</li>
 *   <li>{@code residual} required but may be zero or negative</li>
 * </ul>
 *
 * @param reference  the upstream transaction reference (audited on each ledger line)
 * @param residual   the rounding residual {@code precise - booked} in {@code currency}
 * @param currency   the ISO-4217 currency of {@code residual}
 */
public record RoundingResidualRequest(String reference, BigDecimal residual, String currency) {
}
