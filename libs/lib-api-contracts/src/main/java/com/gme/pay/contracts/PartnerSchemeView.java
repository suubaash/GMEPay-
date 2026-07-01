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
 *
 * <h2>ADR-016 QR-network routing field (additive)</h2>
 *
 * <p>Appended for QR-classified failover routing (ADR-016). Nullable/additive so
 * config-registry can populate it incrementally without breaking existing readers:
 * <ul>
 *   <li>{@code networkIdentifier} — the QR network's globally-unique reverse-domain
 *       / AID GUID (e.g. {@code com.zeropay}, {@code fonepay.com}) that a scanned QR
 *       is classified to. It is the deterministic routing key mapping
 *       QR-network → partner; smart-router resolves this to the ordered candidate
 *       list. Nullable while the row predates the {@code network_identifier}
 *       column population.</li>
 * </ul>
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
        String status,
        String networkIdentifier) {

    /**
     * Backwards-compatible 12-arg constructor (pre-Wave-3 shape). Delegates the
     * five location-resolution fields and the ADR-016 {@code networkIdentifier}
     * to {@code null} so existing producers
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
                approvalMethodMpm, enabled, null, null, null, null, null, null);
    }

    /**
     * Backwards-compatible 17-arg constructor (Wave-3 shape, pre-ADR-016).
     * Delegates {@code networkIdentifier} to {@code null} so existing
     * Wave-3 call sites in config-registry + smart-router keep compiling unchanged.
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
            Boolean enabled,
            String countryCode,
            Boolean supportsCpm,
            Boolean supportsMpm,
            Integer priority,
            String status) {
        this(partnerId, schemeId, direction, role, zeropayMerchantId, zeropaySubMerchantId,
                kftcInstitutionCode, partnerTypeChar, vaultSecretId, approvalMethodCpm,
                approvalMethodMpm, enabled, countryCode, supportsCpm, supportsMpm, priority,
                status, null);
    }
}
