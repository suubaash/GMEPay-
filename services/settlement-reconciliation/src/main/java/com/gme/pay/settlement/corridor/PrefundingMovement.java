package com.gme.pay.settlement.corridor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Leg (b) of the cross-border three-way tie-out: <b>our own prefunding movement</b> — the USD the
 * hub actually deducted from the partner's float for one payment, as prefunding reports it.
 *
 * @param reference the reference the deduct was keyed on — the hub partner reference, so it joins
 *                  directly to {@link SchemeTransactionRecord#joinKey()}
 * @param amountUsd USD deducted (positive)
 * @param at        instant of the movement
 */
public record PrefundingMovement(String reference, BigDecimal amountUsd, Instant at) {
}
