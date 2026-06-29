package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Canonical write payload for one partner-side commission-share row
 * ({@code partner_commission_share}, V031). A partner commission save is a
 * <b>bulk replace</b> of the partner's whole share set, the same multi-row
 * SCD-6 contract as {@link FeeScheduleCommand}. The read shape is
 * {@link PartnerCommissionShareView}.
 *
 * <ul>
 *   <li>{@code schemeId} — scheme this row prices (&le; 40 chars);
 *       {@code null} = all schemes (partner-wide default).</li>
 *   <li>{@code direction} — {@code INBOUND} | {@code OUTBOUND} | {@code BOTH};
 *       {@code null} = all directions. At most one row per
 *       ({@code schemeId}, {@code direction}) pair per save — duplicates are a
 *       400.</li>
 *   <li>{@code partnerSharePct} — the partner's fraction of GME's commission,
 *       decimal STRING on the wire; required, in {@code [0,1]}, at most 4
 *       decimal places (NUMERIC(6,4)). GME keeps {@code 1 - partnerSharePct}.</li>
 * </ul>
 */
public record PartnerCommissionShareCommand(
        String schemeId,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal partnerSharePct) {
}
