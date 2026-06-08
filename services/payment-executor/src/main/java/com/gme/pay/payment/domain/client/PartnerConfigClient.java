package com.gme.pay.payment.domain.client;

import java.math.RoundingMode;

/**
 * Reads a partner's settlement configuration from config-registry (API-only; no shared DB).
 * Used at commit to book the settlement liability under the partner's configured rounding rule.
 */
public interface PartnerConfigClient {

    SettlementConfig getSettlementConfig(long partnerId);

    record SettlementConfig(String settlementCurrency, RoundingMode settlementRoundingMode) {
    }
}
