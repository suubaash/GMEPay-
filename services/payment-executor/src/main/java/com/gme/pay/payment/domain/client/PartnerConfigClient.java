package com.gme.pay.payment.domain.client;

import java.math.RoundingMode;

/**
 * Interface to the Config Registry service (config-registry).
 * Resolves partner configuration (type, settlement rules) for the live payment path.
 *
 * <p>Owned by config-registry per INTER_SERVICE_CONTRACTS.md — payment-executor consumes
 * it only via this DTO surface, never by touching config-registry's database directly.
 */
public interface PartnerConfigClient {

    /**
     * Loads partner configuration from config-registry.
     *
     * @param partnerId the partner identifier
     * @return the partner config view
     * @throws com.gme.pay.payment.domain.PaymentException if the partner is unknown or the
     *         config service is unreachable
     */
    PartnerConfigView loadPartner(String partnerId);

    /**
     * Resolves the effective GROSS merchant fee rate for a (scheme, merchantType) from
     * config-registry's {@code merchant_fee_schedule} (V032) — exact type beats the scheme
     * default. The payment path snapshots the result onto the transaction at creation.
     *
     * <p><b>Non-fatal:</b> returns {@link java.util.Optional#empty()} when no row applies OR
     * config-registry is unreachable — the caller leaves the snapshot null and settlement
     * treats it as 0 (today's behaviour). NEVER fails a payment. The default no-ops to empty
     * so stub/test implementations need not override it.
     *
     * @param schemeId     the scheme code (e.g. {@code "zeropay_kr"})
     * @param merchantType the merchant category, or {@code null} to match only the scheme default
     */
    default java.util.Optional<java.math.BigDecimal> resolveMerchantFeeRate(
            String schemeId, String merchantType) {
        return java.util.Optional.empty();
    }

    /**
     * Immutable view of a partner's configuration as published by config-registry.
     *
     * <p>{@code settlementRoundingMode} is mapped from the JSON string
     * (e.g. {@code "DOWN"}, {@code "HALF_UP"}) to {@link RoundingMode} per MONEY_CONVENTION.md.
     */
    record PartnerConfigView(
            String partnerId,
            String type,
            String settlementCurrency,
            RoundingMode settlementRoundingMode
    ) {}
}
