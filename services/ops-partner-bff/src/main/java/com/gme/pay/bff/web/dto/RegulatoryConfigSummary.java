package com.gme.pay.bff.web.dto;

import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import com.gme.pay.contracts.TravelRuleProtocol;

/**
 * Compliance-overview projection of a partner's regulatory config: four booleans answering "has the
 * per-lane regulatory attribute been configured?" for the admin /compliance readiness board.
 *
 * <p>Derived from representative fields of the FLAT {@link PartnerRegulatoryConfigView} — NOT from
 * whole-section null checks (the view has no nullable section objects; e.g. {@code ctrThresholdKrw} is
 * NOT NULL by contract). A flag being {@code true} means only that the per-partner CONFIG was entered;
 * it does NOT assert that any real BOK/Hometax/KoFIU filing channel exists (those are OI-02/OI-03 gated).
 */
public record RegulatoryConfigSummary(
        boolean bokSet,
        boolean hometaxSet,
        boolean kofiuSet,
        boolean travelRuleSet) {

    /** All-false summary for a partner with no step-8 regulatory save yet. */
    public static final RegulatoryConfigSummary NONE =
            new RegulatoryConfigSummary(false, false, false, false);

    /** Derive the flags from a regulatory config view; {@code null} → {@link #NONE}. */
    public static RegulatoryConfigSummary from(PartnerRegulatoryConfigView v) {
        if (v == null) {
            return NONE;
        }
        return new RegulatoryConfigSummary(
                v.bokTxnCode() != null,
                v.hometaxIssuerCertId() != null,
                v.kofiuEntityId() != null,
                v.travelRuleProtocol() != null && v.travelRuleProtocol() != TravelRuleProtocol.NONE);
    }
}
