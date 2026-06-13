package com.gme.pay.registry.contact;

/**
 * Functional roles a partner contact can hold (Slice 2 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 2 — Contacts"). Mirrors the CHECK
 * constraint {@code ck_partner_contact_role} on {@code partner_contact} (V009) —
 * keep the two in lock-step: adding a role here without widening the CHECK will
 * fail at INSERT, and vice versa leaves a dead enum value.
 *
 * <p>The Slice 8 activation gate requires a partner to cover at least four
 * distinct roles before {@code ONBOARDING → KYB_PENDING}; this enum is the
 * roster that requirement counts over.
 */
public enum ContactRole {

    /** 24x7 operations escalation contact. */
    OPS_24X7,

    /** Finance / settlement contact (reconciliation queries, invoice flow). */
    FINANCE,

    /** Compliance contact — the Money Laundering Reporting Officer. */
    COMPLIANCE_MLRO,

    /** Technical integration contact (API keys, webhooks, certificates). */
    TECH,

    /** Legal counsel / contract contact. */
    LEGAL,

    /** Incident-response contact for security / fraud events. */
    INCIDENT
}
