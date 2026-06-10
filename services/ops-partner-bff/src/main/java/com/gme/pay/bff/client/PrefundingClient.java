package com.gme.pay.bff.client;

import java.math.BigDecimal;

/**
 * Read-only view of the prefunding service. Production calls
 * {@code GET /v1/prefunding/{partnerId}/balance}; Phase-1 default is an
 * in-memory stub.
 */
public interface PrefundingClient {

    /** Fetch the current balance for a partner. Returns {@code null} if unknown. */
    BalanceView getBalance(String partnerId);

    record BalanceView(
            String partnerId,
            String currency,
            BigDecimal balance,
            BigDecimal lowBalanceThreshold
    ) {
        /**
         * True when the balance has fallen at or below the configured threshold.
         * Named without an {@code is}/{@code get} prefix so Jackson does not
         * serialize it as an extra JSON property on the balance payload.
         */
        public boolean belowThreshold() {
            return lowBalanceThreshold != null
                    && balance != null
                    && balance.compareTo(lowBalanceThreshold) <= 0;
        }
    }
}
