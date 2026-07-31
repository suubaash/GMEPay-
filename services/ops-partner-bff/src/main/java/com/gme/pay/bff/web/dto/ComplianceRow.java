package com.gme.pay.bff.web.dto;

/**
 * One row of the admin /compliance regulatory-readiness board (Lane 5, #77 Slice 3). Matches the
 * {@code ComplianceRow} contract documented in admin-ui {@code src/api/complianceApi.js}.
 *
 * @param partnerCode      business code (V003)
 * @param partnerName      romanized legal name (falls back to local name, then the code)
 * @param kybStatus        {@code APPROVED | APPROVED_MANUAL_ATTESTATION | PENDING | REVIEW | HIT |
 *                         NOT_SCREENED | UNKNOWN} — derived from the KYB sanctions screening verdict
 *                         (the only KYB verdict field on {@code KybView}; a richer KYB-approval state
 *                         would need a dedicated field). {@code PENDING} means no KYB row exists yet;
 *                         {@code NOT_SCREENED} means a run completed and screened NOTHING (gap T1-4),
 *                         which is not a pending state and must not be shown as one;
 *                         {@code APPROVED_MANUAL_ATTESTATION} means a human screened the partner
 *                         under a compliance-signed SOP rather than a vendor doing it, and is
 *                         deliberately not collapsed into {@code APPROVED}.
 * @param sanctionsResult  {@code CLEAR | CLEAR_MANUAL_ATTESTATION | NEEDS_REVIEW | HIT |
 *                         NOT_SCREENED_NO_PROVIDER | null} — the KYB {@code screeningStatus}
 *                         verbatim ({@code null} before the first screening run)
 * @param regulatoryConfig per-lane "configured?" flags (BOK / Hometax / KoFIU / Travel Rule) —
 *                         a placeholder value does NOT count as configured (GAP T5-2)
 * @param lifecycleStatus  partner {@code PartnerStatus} name (e.g. {@code LIVE | SUSPENDED | ONBOARDING
 *                         | TERMINATED | DRAFT})
 */
public record ComplianceRow(
        String partnerCode,
        String partnerName,
        String kybStatus,
        String sanctionsResult,
        RegulatoryConfigSummary regulatoryConfig,
        String lifecycleStatus) {
}
