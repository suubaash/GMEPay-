package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical read DTO for a partner's prefunding configuration
 * ({@code partner_prefunding_config}, Slice 5 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 5 — Prefunding"). The JSON shape
 * every consumer of config-registry's prefunding endpoints binds to, mirroring
 * how {@link SettlementConfigView} / {@link KybView} are the single read
 * shapes for their aggregates.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the config ROW: under SCD-6 every
 *       step-5 save mints a fresh row, so the id changes across saves (audit
 *       reference, not a stable identifier).</li>
 *   <li>{@code fundingModel} — {@code PREFUNDED} | {@code POSTPAID} |
 *       {@code HYBRID} (V015 CHECK); String per the {@code settlementMethod} /
 *       {@code riskRating} precedent.</li>
 *   <li>Money fields ({@code openingBalanceUsd},
 *       {@code lowBalanceThresholdUsd}, {@code creditLimitUsd},
 *       {@code collateralAmountUsd}) — {@link BigDecimal} in major USD units,
 *       serialized as decimal STRINGS on the wire per
 *       {@code docs/MONEY_CONVENTION.md} (never floating-point JSON numbers).
 *       Scale-4 normalized server-side (NUMERIC(19,4)).</li>
 *   <li>{@code alertTier70} / {@code alertTier85} / {@code alertTier95} —
 *       which low-balance alert tiers are armed.</li>
 *   <li>{@code autoSuspendOnBreach} — when TRUE a balance breach proposes a
 *       {@code change_request(status:SUSPENDED, proposed_by='system')}
 *       (ADR-008 carve-out), operator approval required to lift.</li>
 *   <li>{@code floatTopUpBankAccountId} — {@code partner_bank_account} row id
 *       (purpose=FLOAT_TOPUP, validated at write time) or {@code null}.</li>
 *   <li>{@code topUpReferencePattern} — wire-reference template; always
 *       contains {@code {partner_code}} (service-enforced).</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time of this row
 *       version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI relies on field presence, same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrefundingConfigView(
        Long id,
        String fundingModel,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalanceUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lowBalanceThresholdUsd,
        Boolean alertTier70,
        Boolean alertTier85,
        Boolean alertTier95,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditLimitUsd,
        Boolean autoSuspendOnBreach,
        Long floatTopUpBankAccountId,
        String topUpReferencePattern,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collateralAmountUsd,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
