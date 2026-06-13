package com.gme.pay.contracts;

/**
 * VAT treatment of GME's fee invoices to a partner, driving the Hometax
 * e-tax-invoice line classification — Slice 8 Lane C (Regulatory attributes).
 * Mirrors the V029 {@code partner_regulatory_config.vat_treatment} CHECK
 * roster; serialized as the bare enum name on the wire.
 */
public enum VatTreatment {

    /** 영세율 — zero-rated export services (cross-border remittance fees). */
    ZERO_RATED_EXPORT,

    /** Standard-rated domestic supply (10% KR VAT). */
    STANDARD,

    /** 면세 — VAT-exempt supply. */
    EXEMPT
}
