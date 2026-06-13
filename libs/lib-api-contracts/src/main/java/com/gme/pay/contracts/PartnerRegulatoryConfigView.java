package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Canonical read DTO for a partner's regulatory configuration
 * ({@code partner_regulatory_config}, V029 — Slice 8 Lane C "Regulatory
 * attributes"). The JSON shape every consumer of config-registry's step-8
 * regulatory endpoints binds to; the write side is
 * {@link PartnerRegulatoryConfigCommand} (riding
 * {@link PartnerCommand.UpdateStep8Regulatory}).
 *
 * <ul>
 *   <li>{@code partnerId} — the partner's BIGINT surrogate (V003/V004); the
 *       URL already names the partner by business code, the surrogate is
 *       echoed so downstream consumers (reporting-compliance, the BOK/Hometax
 *       filing jobs) can join without a second lookup.</li>
 *   <li>BOK 외환거래보고: {@code bokTxnCode} (external-trade code, 3 digits —
 *       placeholder shape pending the OI-03 reference),
 *       {@code bokFxReportingCategory}, {@code bokRemitterType}.</li>
 *   <li>Hometax: {@code hometaxIssuerCertId} (lib-vault document id of the
 *       e-tax-invoice signing certificate), {@code vatTreatment}.</li>
 *   <li>KoFIU: {@code kofiuEntityId}, {@code ctrThresholdKrw} — major-KRW
 *       {@link BigDecimal}, serialized as a decimal STRING on the wire per
 *       {@code docs/MONEY_CONVENTION.md} (never a floating-point JSON
 *       number); never {@code null} on reads (V029 NOT NULL, statutory
 *       default KRW 10,000,000).</li>
 *   <li>PIPA: {@code pipaJurisdictionAllowlist} — the ISO-3166 alpha-2 codes
 *       PII may flow to, exploded from the V029 CSV column by the service
 *       (empty list = none documented); {@code legalBasisCode}.</li>
 *   <li>Travel Rule: {@code travelRuleProtocol},
 *       {@code travelRuleEndpointUrl} (always present when the protocol is
 *       not {@code NONE} — service-enforced), {@code travelRuleThresholdKrw}
 *       (major-KRW decimal STRING; defaults to KRW 1,000,000).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * same contract as {@link PartnerView} / {@link PrefundingConfigView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PartnerRegulatoryConfigView(
        Long partnerId,
        String bokTxnCode,
        BokFxReportingCategory bokFxReportingCategory,
        BokRemitterType bokRemitterType,
        String hometaxIssuerCertId,
        VatTreatment vatTreatment,
        String kofiuEntityId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ctrThresholdKrw,
        List<String> pipaJurisdictionAllowlist,
        LegalBasisCode legalBasisCode,
        TravelRuleProtocol travelRuleProtocol,
        String travelRuleEndpointUrl,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal travelRuleThresholdKrw) {
}
