package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical read DTO for a partner's transaction limits
 * ({@code partner_limits}, Slice 6 — see {@code docs/PARTNER_SETUP_PLAN.md}
 * §"Slice 6 — Commercial Terms"). The JSON shape every consumer of
 * config-registry's limits endpoints binds to, mirroring how
 * {@link PrefundingConfigView} is the single read shape for its aggregate.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the ROW: under SCD-6 every step-6
 *       save mints a fresh row (audit reference, not a stable
 *       identifier).</li>
 *   <li>Money fields ({@code perTxnMinUsd}, {@code perTxnMaxUsd},
 *       {@code dailyCapUsd}, {@code monthlyCapUsd}, {@code annualCapUsd}) —
 *       {@link BigDecimal} in major USD units, decimal STRINGS on the wire
 *       per {@code docs/MONEY_CONVENTION.md}; scale-4 normalized server-side
 *       (NUMERIC(19,4)). {@code null} = that cap is not configured.</li>
 *   <li>{@code licenseType} — regulatory regime discriminator (e.g.
 *       {@code SOAEK_HAEOEMONG} for the Korean 소액해외송금업 licence, which
 *       hard-caps {@code perTxnMaxUsd} at 5,000 and {@code annualCapUsd} at
 *       50,000 — V020 CHECK + server-side enforcement).</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time of this row
 *       version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI relies on field presence, same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LimitsView(
        Long id,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal perTxnMinUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal perTxnMaxUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal dailyCapUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal monthlyCapUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal annualCapUsd,
        String licenseType,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
