package com.gme.pay.bff.client;

import com.gme.pay.contracts.BalanceAlertView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Read-only view of the prefunding service. Production calls
 * {@code GET /v1/prefunding/{partnerId}/balance}; Phase-1 default is an
 * in-memory stub.
 *
 * <p>Slice 5 (5B.1) adds the Admin balance panel surface —
 * {@link #getAdminBalance(String)} (canonical
 * {@link com.gme.pay.contracts.BalanceView} with pctOfThreshold) and
 * {@link #getBalanceAlerts(String)} (tier alert feed). Both are
 * {@code default} methods so the interface stays FUNCTIONAL — several tests
 * implement it as a lambda over {@link #getBalance(String)}.
 */
public interface PrefundingClient {

    /** Fetch the current balance for a partner. Returns {@code null} if unknown. */
    BalanceView getBalance(String partnerId);

    /**
     * Canonical Slice-5 balance view for the Admin UI
     * ({@code GET /v1/admin/partners/{code}/balance}). Default implementation
     * derives it from {@link #getBalance(String)}: pctOfThreshold is
     * {@code balance / threshold * 100} at scale 2 HALF_UP (null when no
     * positive threshold). Returns {@code null} when the partner is unknown.
     * The REST adapter overrides this to bind prefunding's
     * {@code GET /v1/prefunding/{partnerCode}/balance} directly.
     */
    default com.gme.pay.contracts.BalanceView getAdminBalance(String partnerCode) {
        BalanceView legacy = getBalance(partnerCode);
        if (legacy == null) {
            return null;
        }
        BigDecimal pct = null;
        if (legacy.balance() != null && legacy.lowBalanceThreshold() != null
                && legacy.lowBalanceThreshold().signum() > 0) {
            pct = legacy.balance().multiply(new BigDecimal("100"))
                    .divide(legacy.lowBalanceThreshold(), 2, RoundingMode.HALF_UP);
        }
        return com.gme.pay.contracts.BalanceView.of(
                partnerCode, legacy.currency(), legacy.balance(),
                legacy.lowBalanceThreshold(), pct);
    }

    /**
     * Tier alerts for a partner, newest first
     * ({@code GET /v1/admin/partners/{code}/balance-alerts} →
     * prefunding's {@code GET /v1/prefunding/{partnerCode}/alerts}).
     * Default: no alerts.
     */
    default List<BalanceAlertView> getBalanceAlerts(String partnerCode) {
        return List.of();
    }

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
