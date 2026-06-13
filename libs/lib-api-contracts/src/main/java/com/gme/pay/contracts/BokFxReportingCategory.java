package com.gme.pay.contracts;

/**
 * How a partner's flows aggregate into the BOK 외환거래보고 (foreign-exchange
 * transaction report) — Slice 8 Lane C (Regulatory attributes). Mirrors the
 * V029 {@code partner_regulatory_config.bok_fx_reporting_category} CHECK
 * roster; serialized as the bare enum name on the wire.
 */
public enum BokFxReportingCategory {

    /** Individual remittances aggregated per-remitter for the BOK filing. */
    INDIVIDUAL_AGGREGATE,

    /** Institution-level reporting (the partner files as a single institution). */
    INSTITUTIONAL
}
