package com.gme.pay.domain;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * A partner that connects to GMEPay+. Owned by config-registry. Carries the per-partner
 * {@code settlementRoundingMode} that dictates how the partner's settlement liability is booked
 * at transaction creation (e.g. a partner that books round-DOWN to 2dp vs GMEPay+ default HALF_UP).
 * The residual between the precise amount and the booked amount is posted to the rounding ledger.
 */
public record Partner(
        String partnerId,
        PartnerType type,
        String settlementCurrency,
        RoundingMode settlementRoundingMode) {

    public Partner {
        Objects.requireNonNull(partnerId, "partnerId required");
        Objects.requireNonNull(type, "type required");
        if (settlementRoundingMode == null) {
            settlementRoundingMode = RoundingMode.HALF_UP; // default policy
        }
    }

    /** Create a partner with the default HALF_UP settlement rounding. */
    public static Partner of(String partnerId, PartnerType type, String settlementCurrency) {
        return new Partner(partnerId, type, settlementCurrency, RoundingMode.HALF_UP);
    }
}
