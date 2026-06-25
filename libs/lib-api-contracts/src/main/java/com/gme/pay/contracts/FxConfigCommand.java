package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Canonical write payload for a partner's FX configuration (Slice 6 —
 * Commercial Terms, see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6"). Rides
 * inside {@link PartnerCommand.UpdateStep6Commercial#fxConfig()} — full-state
 * replace of the {@code partner_fx_config} row (SCD-6 paired write per
 * ADR-010), the same single-row discipline as
 * {@link PartnerCommand.UpdateStep5}. The read shape is {@link FxConfigView}.
 *
 * <ul>
 *   <li>{@code marginBps} — &ge; 0, at most 4 decimal places and 3 integer
 *       digits (NUMERIC(7,4), i.e. &le; 999.9999 bps), decimal STRING on the
 *       wire; {@code null} defaults to {@code 0}.</li>
 *   <li>{@code referenceRateSource} — required; {@code SEOUL_FX_BROKER} |
 *       {@code PARTNER_PROVIDED} | {@code MID_MARKET} (the V019 CHECK
 *       roster). String per the {@code settlementMethod} precedent —
 *       config-registry validates the roster.</li>
 *   <li>{@code quoteHoldSeconds} — {@code 60..1800} (V019 CHECK);
 *       {@code null} defaults to {@code 300}.</li>
 * </ul>
 */
public record FxConfigCommand(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal marginBps,
        String referenceRateSource,
        Integer quoteHoldSeconds,
        Boolean disclosedPartnerMargin) {

    /**
     * Back-compat constructor for the original three fields — {@code disclosedPartnerMargin}
     * (Step 10, OPTIONAL transparency flag: is the partner's FX margin disclosed?) defaults to
     * {@code null}, treated as {@code false}. Keeps existing callers/tests compiling during the
     * Expand-phase rollout; non-blocking and recorded for reporting only.
     */
    public FxConfigCommand(BigDecimal marginBps, String referenceRateSource, Integer quoteHoldSeconds) {
        this(marginBps, referenceRateSource, quoteHoldSeconds, null);
    }
}
