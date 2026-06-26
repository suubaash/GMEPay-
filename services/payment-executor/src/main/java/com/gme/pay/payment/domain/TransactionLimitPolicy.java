package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.client.PartnerConfigClient;

import java.math.BigDecimal;

/**
 * Pure per-transaction limit rule over a partner's configured {@link PartnerConfigClient.TxnLimits}
 * (V020 partner_limits). Enforces the per-transaction USD min/max — a {@code null} cap is unconstrained,
 * and {@code null} limits/amount is a no-op (fail-open, matching the contract). Throws
 * {@link TransactionLimitExceededException} on breach.
 *
 * <p>Deliberately stateless: the rolling daily/monthly/annual caps are CUMULATIVE and require
 * transaction-history aggregation (a stateful follow-up); they are not evaluated here.
 */
public final class TransactionLimitPolicy {

    private TransactionLimitPolicy() {
    }

    /**
     * @param partnerId for the rejection message
     * @param amountUsd the transaction value in major USD units (USD equivalent of the agreed collection
     *                  amount); {@code null} skips the check
     * @param limits    the partner's configured limits, or {@code null} = unconstrained
     */
    public static void enforcePerTransaction(String partnerId, BigDecimal amountUsd,
                                             PartnerConfigClient.TxnLimits limits) {
        if (limits == null || amountUsd == null) {
            return;
        }
        if (limits.perTxnMaxUsd() != null && amountUsd.compareTo(limits.perTxnMaxUsd()) > 0) {
            throw new TransactionLimitExceededException(partnerId, amountUsd, limits.perTxnMaxUsd(), "MAX");
        }
        if (limits.perTxnMinUsd() != null && amountUsd.compareTo(limits.perTxnMinUsd()) < 0) {
            throw new TransactionLimitExceededException(partnerId, amountUsd, limits.perTxnMinUsd(), "MIN");
        }
    }
}
