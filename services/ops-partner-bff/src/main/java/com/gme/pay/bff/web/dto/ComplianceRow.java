package com.gme.pay.bff.web.dto;

/**
 * One row of the admin /compliance regulatory-readiness board (Lane 5, #77 Slice 3). Matches the
 * {@code ComplianceRow} contract documented in admin-ui {@code src/api/complianceApi.js}.
 *
 * @param partnerCode      business code (V003)
 * @param partnerName      romanized legal name (falls back to local name, then the code)
 * @param kybStatus        {@code APPROVED | PENDING | REVIEW | HIT} — derived from the KYB sanctions
 *                         screening verdict (the only KYB verdict field on {@code KybView}; a richer
 *                         KYB-approval state would need a dedicated field). {@code PENDING} when no
 *                         KYB row exists yet.
 * @param sanctionsResult  {@code CLEAR | NEEDS_REVIEW | HIT | null} — the KYB {@code screeningStatus}
 *                         verbatim ({@code null} before the first screening run)
 * @param regulatoryConfig per-lane "configured?" flags (BOK / Hometax / KoFIU / Travel Rule)
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
