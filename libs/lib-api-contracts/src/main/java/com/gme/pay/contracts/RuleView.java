package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical read DTO for one persisted pricing rule ({@code partner_rule},
 * V017) — Slice 6 (Commercial Terms). The write side is {@link RuleCommand}
 * (one element of {@link PartnerCommand.UpdateStep6Rules}).
 *
 * <ul>
 *   <li>{@code id} — V017 BIGSERIAL surrogate of the CURRENT row. Note that a
 *       bulk replace mints fresh ids (SCD-6: rows are superseded, never
 *       updated), so ids are stable only between writes.</li>
 *   <li>{@code schemeId} / {@code direction} — the rule key alongside the
 *       partner (identified by the URL). Direction is {@code INBOUND} |
 *       {@code OUTBOUND} | {@code BOTH}.</li>
 *   <li>{@code mA} / {@code mB} — margins as decimal FRACTIONS
 *       ({@code 0.0150} = 1.50%), scale-4 normalized, decimal STRINGS on the
 *       wire (same convention as money per {@code docs/MONEY_CONVENTION.md}).</li>
 *   <li>{@code serviceChargeUsd} — flat per-transaction charge in major USD
 *       units, scale-4 normalized, decimal STRING on the wire.</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (half-open business-time window) and {@code recordedAt} (transaction
 *       time of this version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RuleView(
        Long id,
        String schemeId,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal mA,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal mB,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal serviceChargeUsd,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
