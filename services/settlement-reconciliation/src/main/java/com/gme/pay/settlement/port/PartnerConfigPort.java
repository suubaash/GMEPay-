package com.gme.pay.settlement.port;

import java.math.RoundingMode;

/**
 * Anti-corruption port to config-registry for the per-partner settlement configuration the
 * outbound spine needs (Spec Addendum 001 rounding mode + settle currency). The real impl calls
 * {@code GET /v1/partners/{partnerCode}}; settlement-reconciliation NEVER reads config-registry's DB.
 */
public interface PartnerConfigPort {

    /**
     * @param partnerCode the human partner code (the {@code GET /v1/partners/{id}} path variable).
     *                    On the ZeroPay settlement path this is keyed off the merchant id — see the
     *                    documented merchantId→partnerCode caveat.
     * @return the partner's settle currency + rounding mode; safe defaults (KRW / HALF_UP) when the
     *         partner is unknown or config-registry is unreachable (never throws).
     */
    PartnerSettlementConfig resolve(String partnerCode);

    record PartnerSettlementConfig(String partnerCode, String settleCcy, RoundingMode mode) {
        /** Addendum-001 defaults: HALF_UP, KRW. */
        public static PartnerSettlementConfig defaults(String partnerCode) {
            return new PartnerSettlementConfig(partnerCode, "KRW", RoundingMode.HALF_UP);
        }
    }
}
