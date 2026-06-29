package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical read DTO for a partner's FX configuration
 * ({@code partner_fx_config}, Slice 6 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6 — Commercial Terms"). The JSON
 * shape every consumer of config-registry's FX-config endpoints binds to,
 * mirroring how {@link SettlementConfigView} / {@link PrefundingConfigView}
 * are the single read shapes for their aggregates.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the config ROW: under SCD-6 every
 *       step-6 save mints a fresh row, so the id changes across saves (audit
 *       reference, not a stable identifier).</li>
 *   <li>{@code marginBps} — FX margin in basis points layered on the
 *       reference rate; NUMERIC(7,4), decimal STRING on the wire (the
 *       {@code docs/MONEY_CONVENTION.md} never-a-float rule applies to bps
 *       too).</li>
 *   <li>{@code referenceRateSource} — {@code SEOUL_FX_BROKER} |
 *       {@code PARTNER_PROVIDED} | {@code MID_MARKET} (V019 CHECK); String
 *       per the {@code settlementMethod} / {@code riskRating} precedent.</li>
 *   <li>{@code quoteHoldSeconds} — how long an issued quote stays honoured;
 *       {@code 60..1800} (V019 CHECK).</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time of this row
 *       version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI relies on field presence, same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FxConfigView(
        Long id,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal marginBps,
        String referenceRateSource,
        Integer quoteHoldSeconds,
        Boolean disclosedPartnerMargin,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
