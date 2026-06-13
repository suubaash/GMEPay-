package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Canonical write payload for one partner fee-schedule row (Slice 6 —
 * Commercial Terms, see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6"). Rides
 * inside {@link PartnerCommand.UpdateStep6Commercial#feeSchedules()} — the
 * step-6 save is a <b>bulk replace</b> of the partner's whole fee set, the
 * same multi-row contract as {@link BankAccountCommand}. The read shape is
 * {@link FeeScheduleView}.
 *
 * <ul>
 *   <li>{@code schemeId} — scheme this row prices (&le; 40 chars, e.g.
 *       {@code "zeropay_kr"}); {@code null} = all schemes (partner-wide
 *       default row).</li>
 *   <li>{@code direction} — {@code INBOUND} | {@code OUTBOUND} | {@code BOTH}
 *       (the V017/V018 CHECK roster); {@code null} = all directions. At most
 *       one row per ({@code schemeId}, {@code direction}) pair per save —
 *       duplicates are a 400.</li>
 *   <li>{@code fixedFeeUsd} — flat per-transaction fee, major USD units,
 *       decimal STRING on the wire per {@code docs/MONEY_CONVENTION.md};
 *       &ge; 0, at most 4 decimal places (NUMERIC(19,4)); {@code null}
 *       defaults to {@code 0}.</li>
 *   <li>{@code bpsFee} — variable fee in basis points; &ge; 0, NUMERIC(7,4)
 *       shape (&le; 999.9999); {@code null} defaults to {@code 0}.</li>
 *   <li>{@code tiers} — optional volume bands; each needs both fields,
 *       {@code fromVolumeUsd} strictly ascending across the list;
 *       {@code null}/empty = flat pricing.</li>
 * </ul>
 */
public record FeeScheduleCommand(
        String schemeId,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fixedFeeUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bpsFee,
        List<FeeTier> tiers) {
}
