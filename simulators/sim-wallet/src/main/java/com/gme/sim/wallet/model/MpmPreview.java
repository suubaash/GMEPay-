package com.gme.sim.wallet.model;

/**
 * Preview shown to the customer after they scan a merchant MPM QR.
 * amount is null when mode=static (customer will enter it).
 */
public record MpmPreview(
        String merchantName,
        String mode,     // "static" | "dynamic"
        String amount,   // String/null — BigDecimal on wire
        String currency
) {}
