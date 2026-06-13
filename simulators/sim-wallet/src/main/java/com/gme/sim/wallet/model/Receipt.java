package com.gme.sim.wallet.model;

import java.time.ZonedDateTime;

/**
 * Immutable receipt returned after a successful /v1/wallet/pay call.
 * Money amounts are String to preserve BigDecimal precision on the wire.
 */
public record Receipt(
        String id,
        String partner,
        String mode,
        String payAmountKrw,
        String serviceFeeKrw,
        boolean fxApplied,
        String chargeCurrency,
        String chargeAmount,
        String fxRate,           // null when fxApplied=false
        String schemeTxnRef,
        ZonedDateTime committedAt
) {}
