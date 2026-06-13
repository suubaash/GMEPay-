package com.gme.pay.registry.document;

/**
 * KYB document classes a partner can have on file (Slice 3 — wizard step 3).
 * Mirrors the V010 {@code ck_partner_document_doc_type} CHECK constraint; the
 * two rosters must stay in lockstep (an enum value the CHECK rejects would fail
 * at INSERT, a CHECK value missing here would 400 at the API).
 */
public enum DocumentType {
    /** Remittance/PSP license scan issued by the home regulator. */
    LICENSE,
    /** Certificate of incorporation / corporate registry extract. */
    CERT_INCORPORATION,
    /** Articles of association. */
    AOA,
    /** Board resolution authorizing the partnership / signatories. */
    BOARD_RESOLUTION,
    /** Ultimate-beneficial-owner declaration. */
    UBO_DECLARATION,
    /** Audited financial statements. */
    FINANCIALS,
    /** Wolfsberg Correspondent Banking Due Diligence Questionnaire pack. */
    CBDDQ,
    /** Anything else an operator needs on file. */
    OTHER
}
