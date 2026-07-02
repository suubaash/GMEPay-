package com.gme.sim.gmeremit.model;

/**
 * Immutable record of a completed wallet payment.
 *
 * <p>{@code payAmount} / {@code currency} are the amount and currency the MERCHANT receives
 * (KRW for domestic ZeroPay, NPR for a cross-border Nepal payment). {@code chargedKrw} is what
 * the user's KRW wallet is debited (payment converted to KRW where applicable + fee).
 */
public record WalletTransaction(
        String schemeTxnRef,
        String merchantName,
        String currency,
        String payAmount,
        String feeKrw,
        String chargedKrw,
        String committedAt
) {}
