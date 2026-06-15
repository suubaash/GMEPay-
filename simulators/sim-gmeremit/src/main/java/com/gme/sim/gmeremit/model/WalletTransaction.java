package com.gme.sim.gmeremit.model;

/**
 * Immutable record of a completed wallet payment.
 */
public record WalletTransaction(
        String schemeTxnRef,
        String merchantName,
        String payAmountKrw,
        String feeKrw,
        String chargedKrw,
        String committedAt
) {}
