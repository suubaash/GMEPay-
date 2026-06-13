package com.gme.pay.contracts;

/**
 * Remitter classification on the BOK 외환거래보고 filing — Slice 8 Lane C
 * (Regulatory attributes). Mirrors the V029
 * {@code partner_regulatory_config.bok_remitter_type} CHECK roster;
 * serialized as the bare enum name on the wire.
 */
public enum BokRemitterType {

    INDIVIDUAL,

    CORPORATION,

    GOVERNMENT,

    FINANCIAL_INSTITUTION
}
