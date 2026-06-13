package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Canonical read DTO for one persisted scheme enablement
 * ({@code partner_scheme}, V022) — Slice 7 (Scheme Enablement). The write side
 * is {@link PartnerSchemeCommand} (one element of
 * {@link PartnerCommand.UpdateStep7Schemes}).
 *
 * <ul>
 *   <li>{@code partnerId} — the V003/V004 BIGINT partner surrogate the row
 *       belongs to (the URL carries the business code; the surrogate is
 *       echoed for cross-referencing scheme rows in bulk reads).</li>
 *   <li>{@code schemeId} / {@code direction} / {@code role} — the enablement
 *       key and participation shape (V022 CHECK rosters).</li>
 *   <li>ZeroPay wiring ({@code zeropayMerchantId}, {@code zeropaySubMerchantId},
 *       {@code kftcInstitutionCode}, {@code partnerTypeChar}) — nullable while
 *       the draft is incomplete.</li>
 *   <li>{@code vaultSecretId} — opaque ADR-006 vault locator; never the secret
 *       bytes themselves.</li>
 *   <li>{@code approvalMethodCpm} / {@code approvalMethodMpm} —
 *       {@code CONFIRMATION} | {@code SILENT}; nullable.</li>
 *   <li>{@code enabled} — the kill switch; a disabled row keeps its wiring but
 *       routes nothing.</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * same contract as {@link PartnerView} / {@link RuleView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PartnerSchemeView(
        Long partnerId,
        String schemeId,
        String direction,
        String role,
        String zeropayMerchantId,
        String zeropaySubMerchantId,
        String kftcInstitutionCode,
        String partnerTypeChar,
        String vaultSecretId,
        String approvalMethodCpm,
        String approvalMethodMpm,
        Boolean enabled) {
}
