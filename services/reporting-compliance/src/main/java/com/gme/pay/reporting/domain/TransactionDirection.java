package com.gme.pay.reporting.domain;

/**
 * Direction of a committed payment transaction.
 * INBOUND  – funds arrive in Korea (maps to BOK FX1015).
 * OUTBOUND – funds leave Korea (maps to BOK FX1014).
 * DOMESTIC – same-currency KRW-to-KRW; BOK exempt.
 * HUB      – intra-hub multi-leg; defaults to FX1015 pending OI-03.
 */
public enum TransactionDirection {
    INBOUND,
    OUTBOUND,
    DOMESTIC,
    HUB
}
