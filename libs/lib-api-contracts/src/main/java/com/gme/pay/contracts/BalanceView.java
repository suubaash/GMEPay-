package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Canonical read DTO for a partner's prefunding balance (Slice 5 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 5 — Prefunding"). Returned by
 * prefunding's {@code GET /v1/prefunding/{partnerCode}/balance} and relayed
 * verbatim by the BFF's {@code GET /v1/admin/partners/{code}/balance}.
 *
 * <ul>
 *   <li>{@code partnerCode} — the partner's natural key (the row key of
 *       {@code partner_balance}).</li>
 *   <li>{@code currency} — ISO-4217 of the balance (USD for OVERSEAS
 *       prefunding).</li>
 *   <li>{@code balance} / {@code threshold} — money as {@link BigDecimal},
 *       serialized as a decimal <em>string</em> on the wire per
 *       {@code docs/MONEY_CONVENTION.md} (never floating point).</li>
 *   <li>{@code pctOfThreshold} — {@code balance / threshold * 100}, scale 2
 *       HALF_UP; the tier-alert percentage the Admin UI gauges against
 *       (TIER_95 / TIER_85 / TIER_70 fire as this falls through 95/85/70).
 *       {@code null} when no positive threshold is configured.</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI relies on field presence, same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BalanceView(
        String partnerCode,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal threshold,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pctOfThreshold) {
}
