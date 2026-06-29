package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Canonical write payload for one gross-merchant-fee row
 * ({@code merchant_fee_schedule}, V032). A merchant-fee save is a <b>bulk
 * replace</b> of the scheme's whole rate set (one row per merchant type), the
 * same SCD-6 contract as {@link SchemeCommissionShareCommand}. The read shape
 * is {@link MerchantFeeScheduleView}.
 *
 * <ul>
 *   <li>{@code merchantType} — merchant category (&le; 40 chars); {@code null}
 *       = the scheme's default rate. At most one row per type per save —
 *       duplicates are a 400.</li>
 *   <li>{@code merchantFeePct} — gross fee rate, decimal STRING; required, in
 *       {@code [0,1]}, at most 4 decimal places (NUMERIC(7,4), e.g.
 *       {@code "0.0080"} = 0.80%).</li>
 * </ul>
 */
public record MerchantFeeScheduleCommand(
        String merchantType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal merchantFeePct) {
}
