package com.gme.pay.settlement.model;

/**
 * Strategy that determines the settlement type for a given partner.
 * Implementations must be deterministic and config-driven (no hardcoded partner names).
 */
public interface SettlementClassifier {

    /**
     * Classify the settlement type for {@code partner}.
     *
     * @throws IllegalArgumentException if the partner type cannot be mapped
     */
    SettlementType classify(Partner partner);
}
