package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalTime;

/**
 * Canonical read DTO for a partner's settlement configuration
 * ({@code partner_settlement_config}, Slice 4 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 4 — Banking &amp; Settlement").
 * The JSON shape every consumer of config-registry's settlement endpoints
 * binds to, mirroring how {@link PartnerView} / {@link KybView} are the single
 * read shapes for their aggregates.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the config ROW: under SCD-6 every
 *       step-4 save mints a fresh row, so the id changes across saves (audit
 *       reference, not a stable identifier).</li>
 *   <li>{@code cycleTPlusN} — settlement cycle in BUSINESS days after the
 *       value date ({@code 0..5}, V013 CHECK).</li>
 *   <li>{@code cutoffTime} / {@code cutoffTimezone} — the local-time cutoff
 *       and the IANA zone it is evaluated in; transactions after the cutoff
 *       book to the next value date.</li>
 *   <li>{@code settlementMethod} — payout rail: {@code SWIFT_MT103} |
 *       {@code KR_FIRM_BANKING} | {@code BAKONG} | {@code NAPAS_247} |
 *       {@code PROMPT_PAY} | {@code FAST_SG} | {@code OTHER} (V013 CHECK);
 *       String per the {@code riskRating} precedent.</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time of this row
 *       version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI relies on field presence, same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SettlementConfigView(
        Long id,
        Integer cycleTPlusN,
        LocalTime cutoffTime,
        String cutoffTimezone,
        String settlementMethod,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
