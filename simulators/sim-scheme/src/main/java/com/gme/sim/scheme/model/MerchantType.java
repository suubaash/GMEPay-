package com.gme.sim.scheme.model;

import java.math.BigDecimal;

/**
 * ZeroPay merchant fee tier.
 * <p>
 * SMALL_BIZ – "zero pay" tier: effective fee rate 0.0000 (no fee charged to merchant).
 * GENERAL   – standard tier: fee rate 0.0080 (0.80 %).
 */
public enum MerchantType {

    SMALL_BIZ(new BigDecimal("0.0000")),
    GENERAL(new BigDecimal("0.0080"));

    public final BigDecimal feeRate;

    MerchantType(BigDecimal feeRate) {
        this.feeRate = feeRate;
    }
}
