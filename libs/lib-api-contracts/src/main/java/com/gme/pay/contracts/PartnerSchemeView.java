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
 * <h2>Wave-3 location-resolution fields (additive)</h2>
 *
 * <p>Appended for smart-router's data-driven scheme-for-location resolution
 * (it consumes this DTO via REST). All nullable/additive so config-registry can
 * populate them incrementally without breaking existing readers:
 * <ul>
 *   <li>{@code countryCode} — ISO-3166 alpha-2 the enablement covers; nullable.</li>
 *   <li>{@code supportsCpm} / {@code supportsMpm} — capability flags for the two
 *       presentment modes (derived from {@code approvalMethodCpm}/{@code Mpm}
 *       presence + a future modes column); nullable while underived.</li>
 *   <li>{@code priority} — selection order when several rows match a location;
 *       lower wins. Nullable.</li>
 *   <li>{@code status} — enablement lifecycle string (e.g. {@code ACTIVE} /
 *       {@code SUSPENDED}); orthogonal to {@code enabled}. Nullable.</li>
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
        Boolean enabled,
        String countryCode,
        Boolean supportsCpm,
        Boolean supportsMpm,
        Integer priority,
        String status) {

    /**
     * Backwards-compatible 12-arg constructor (pre-Wave-3 shape). Delegates the
     * five location-resolution fields to {@code null} so existing producers
     * (config-registry {@code PartnerSchemeEntity.toView}) and readers keep
     * compiling unchanged.
     */
    public PartnerSchemeView(
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
        this(partnerId, schemeId, direction, role, zeropayMerchantId, zeropaySubMerchantId,
                kftcInstitutionCode, partnerTypeChar, vaultSecretId, approvalMethodCpm,
                approvalMethodMpm, enabled, null, null, null, null, null);
    }
}
