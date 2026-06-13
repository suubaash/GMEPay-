package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical read DTO for one prefunding low-balance alert row
 * ({@code balance_alert}, Slice 5 — see {@code docs/PARTNER_SETUP_PLAN.md}
 * §"Slice 5 — Prefunding"). Returned newest-first by prefunding's
 * {@code GET /v1/prefunding/{partnerCode}/alerts} and relayed by the BFF's
 * {@code GET /v1/admin/partners/{code}/balance-alerts}.
 *
 * <ul>
 *   <li>{@code tier} — {@code TIER_95} | {@code TIER_85} | {@code TIER_70}
 *       (balance crossed DOWN through that percentage of the low-balance
 *       threshold) or {@code BREACH} (balance went negative; a system
 *       change_request proposing SUSPENDED is raised alongside).</li>
 *   <li>{@code balanceUsd} / {@code thresholdUsd} — the balance and threshold
 *       at the moment the alert fired; decimal strings on the wire per
 *       {@code docs/MONEY_CONVENTION.md}.</li>
 *   <li>{@code acknowledged} — operator ack; the evaluator re-raises a tier
 *       only after the previous alert for that tier was acknowledged
 *       (hysteresis: oscillation around a boundary does not re-alert).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BalanceAlertView(
        Long id,
        String partnerCode,
        String tier,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balanceUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal thresholdUsd,
        Instant raisedAt,
        Boolean acknowledged) {
}
