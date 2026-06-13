package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Canonical write payload for the wizard's step-8 regulatory panel — Slice 8
 * Lane C "Regulatory attributes". Same shape as
 * {@link PartnerRegulatoryConfigView} minus {@code partnerId} (the URL
 * identifies which partner is being mutated, not the body). Rides
 * {@link PartnerCommand.UpdateStep8Regulatory}; the save is a full-state
 * replace of the {@code partner_regulatory_config} row (SCD-6 paired write
 * per ADR-010), the same discipline as {@link PartnerCommand.UpdateStep5}.
 *
 * <p>The enum-rostered fields ride as STRINGS validated server-side against
 * the {@link BokFxReportingCategory} / {@link BokRemitterType} /
 * {@link VatTreatment} / {@link LegalBasisCode} / {@link TravelRuleProtocol}
 * rosters — the {@code settlementMethod} / {@code fundingModel} precedent
 * (config-registry returns an indexed, friendly 400 instead of a Jackson
 * deserialization error).
 *
 * <ul>
 *   <li>{@code bokTxnCode} — BOK external-trade code; placeholder shape
 *       {@code ^\d{3}$} pending the OI-03 reference (service-enforced).</li>
 *   <li>{@code ctrThresholdKrw} / {@code travelRuleThresholdKrw} — major-KRW
 *       {@link BigDecimal}, decimal STRINGS on the wire per
 *       {@code docs/MONEY_CONVENTION.md}; at most 2 decimal places
 *       (NUMERIC(18,2)), strictly positive. {@code null} defaults to the V029
 *       statutory defaults (KRW 10,000,000 CTR / KRW 1,000,000 Travel
 *       Rule).</li>
 *   <li>{@code pipaJurisdictionAllowlist} — ISO-3166 alpha-2 UPPERCASE codes;
 *       stored as CSV (V029 TEXT), parsed + validated server-side.
 *       {@code null} and the empty list both mean "none documented".</li>
 *   <li>{@code travelRuleEndpointUrl} — REQUIRED whenever
 *       {@code travelRuleProtocol} is present and not {@code NONE}
 *       (service-enforced); at most 500 characters.</li>
 * </ul>
 */
public record PartnerRegulatoryConfigCommand(
        String bokTxnCode,
        String bokFxReportingCategory,
        String bokRemitterType,
        String hometaxIssuerCertId,
        String vatTreatment,
        String kofiuEntityId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ctrThresholdKrw,
        List<String> pipaJurisdictionAllowlist,
        String legalBasisCode,
        String travelRuleProtocol,
        String travelRuleEndpointUrl,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal travelRuleThresholdKrw) {
}
