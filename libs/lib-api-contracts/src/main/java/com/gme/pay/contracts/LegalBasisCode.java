package com.gme.pay.contracts;

/**
 * PIPA legal basis under which PII crosses the border to a partner's
 * jurisdiction — Slice 8 Lane C (Regulatory attributes). Mirrors the V029
 * {@code partner_regulatory_config.legal_basis_code} CHECK roster (the
 * GDPR-aligned six-basis taxonomy PIPA's 2023 amendment converges on);
 * serialized as the bare enum name on the wire.
 */
public enum LegalBasisCode {

    CONSENT,

    CONTRACT,

    LEGAL_OBLIGATION,

    VITAL_INTEREST,

    PUBLIC_TASK,

    LEGITIMATE_INTEREST
}
