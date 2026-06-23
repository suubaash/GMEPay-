package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Canonical write payload for one scheme-side commission-share row
 * ({@code scheme_commission_share}, V031). A scheme commission save is a
 * <b>bulk replace</b> of the scheme's whole share set (one row per direction),
 * the same multi-row SCD-6 contract as {@link FeeScheduleCommand}. The read
 * shape is {@link SchemeCommissionShareView}.
 *
 * <ul>
 *   <li>{@code direction} — {@code INBOUND} | {@code OUTBOUND} | {@code BOTH};
 *       {@code null} = all directions. At most one row per direction per save
 *       — duplicates are a 400.</li>
 *   <li>{@code gmeSharePct} — GME's fraction of the net merchant fee, decimal
 *       STRING on the wire; required, in {@code (0,1]}, at most 4 decimal
 *       places (NUMERIC(6,4)). The scheme keeps {@code 1 - gmeSharePct}.</li>
 *   <li>{@code vanFeePct} — VAN intermediary rate deducted before the split;
 *       {@code >= 0}, NUMERIC(7,4) shape; {@code null} defaults to {@code 0}.</li>
 * </ul>
 */
public record SchemeCommissionShareCommand(
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal gmeSharePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal vanFeePct) {
}
