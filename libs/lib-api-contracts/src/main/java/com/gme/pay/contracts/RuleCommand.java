package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Canonical write payload for ONE pricing rule on the wizard's step-6 rule
 * editor (Slice 6 — Commercial Terms, see {@code docs/PARTNER_SETUP_PLAN.md}
 * §"Slice 6"). Rules ride {@link PartnerCommand.UpdateStep6Rules} as a list —
 * bulk-replace semantics, the same contract as {@link ContactCommand} /
 * {@link BankAccountCommand}.
 *
 * <p>A rule is keyed by (partner × {@code schemeId} × {@code direction}); the
 * partner is identified by the URL, so this payload carries only the scheme +
 * direction half of the key plus the priced values.
 *
 * <ul>
 *   <li>{@code schemeId} — required, &le; 40 chars (e.g. {@code ZEROPAY}).
 *       Free-form until the Slice 7 scheme registry lands.</li>
 *   <li>{@code direction} — required; {@code INBOUND} | {@code OUTBOUND} |
 *       {@code BOTH} (the V017 CHECK roster). String per the
 *       {@code settlementMethod} / {@code fundingModel} precedent —
 *       config-registry validates the roster.</li>
 *   <li>{@code mA} / {@code mB} — required partner-side / GME-side margins as
 *       decimal FRACTIONS ({@code 0.0150} = 1.50%), {@link BigDecimal} carried
 *       as decimal STRINGS on the wire (same convention as money,
 *       {@code docs/MONEY_CONVENTION.md}); &ge; 0, at most 4 decimal places
 *       (NUMERIC(7,4)). The cross-border floor {@code mA + mB >= 0.02} (and
 *       the same-currency zero-margin rule) is enforced server-side via the
 *       lib-domain {@code Rule.validate} invariant against the partner's
 *       collection/settle currency split.</li>
 *   <li>{@code serviceChargeUsd} — flat per-transaction charge in major USD
 *       units, &ge; 0, at most 4 decimal places (NUMERIC(19,4));
 *       {@code null} defaults to {@code 0}.</li>
 * </ul>
 */
public record RuleCommand(
        String schemeId,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal mA,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal mB,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal serviceChargeUsd) {
}
