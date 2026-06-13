package com.gme.pay.contracts;

/**
 * Protocol used to exchange Travel-Rule originator/beneficiary data with a
 * partner on transfers at or above the configured KRW threshold — Slice 8
 * Lane C (Regulatory attributes). Mirrors the V029
 * {@code partner_regulatory_config.travel_rule_protocol} CHECK roster;
 * serialized as the bare enum name on the wire.
 *
 * <p>When the protocol is anything but {@link #NONE} the configuration must
 * also carry a {@code travelRuleEndpointUrl} (service-enforced).
 */
public enum TravelRuleProtocol {

    /** Travel Rule Protocol (OpenVASP / TRP). */
    TRP,

    /** Sygna Bridge. */
    SYGNA,

    /** Direct IVMS101 payload exchange. */
    IVMS101,

    /** Partner is out of Travel-Rule scope; no endpoint required. */
    NONE
}
