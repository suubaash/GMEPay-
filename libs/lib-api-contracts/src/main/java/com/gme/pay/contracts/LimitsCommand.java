package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Canonical write payload for a partner's transaction limits (Slice 6 —
 * Commercial Terms, see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6"). Rides
 * inside {@link PartnerCommand.UpdateStep6Commercial#limits()} — full-state
 * replace of the {@code partner_limits} row (SCD-6 paired write per ADR-010),
 * the same single-row discipline as {@link FxConfigCommand}. The read shape
 * is {@link LimitsView}.
 *
 * <ul>
 *   <li>All money fields — &ge; 0, at most 4 decimal places (NUMERIC(19,4)),
 *       decimal STRINGS on the wire per {@code docs/MONEY_CONVENTION.md};
 *       {@code null} = that cap is not configured. Ordering is validated when
 *       both ends are present: {@code perTxnMinUsd <= perTxnMaxUsd},
 *       {@code dailyCapUsd <= monthlyCapUsd <= annualCapUsd}.</li>
 *   <li>{@code licenseType} — optional regime discriminator (&le; 30 chars).
 *       {@code SOAEK_HAEOEMONG} (소액해외송금업) is hard-enforced server-side:
 *       {@code perTxnMaxUsd} must be PRESENT and &le; 5,000 USD and
 *       {@code annualCapUsd} PRESENT and &le; 50,000 USD; anything else is a
 *       400 (the V020 CHECK is the storage-level backstop).</li>
 * </ul>
 */
public record LimitsCommand(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal perTxnMinUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal perTxnMaxUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal dailyCapUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal monthlyCapUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal annualCapUsd,
        String licenseType) {
}
