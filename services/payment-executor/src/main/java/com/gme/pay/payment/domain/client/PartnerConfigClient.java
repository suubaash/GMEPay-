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
