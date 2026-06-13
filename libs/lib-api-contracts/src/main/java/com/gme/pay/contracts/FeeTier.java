package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * One volume band of a partner fee schedule (Slice 6 — Commercial Terms, see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6"): from rolling volume
 * {@code fromVolumeUsd} upward, {@code bpsOverride} applies instead of the
 * row's flat {@code bpsFee}. Shared by {@link FeeScheduleView} (read) and
 * {@link FeeScheduleCommand} (write) so the wire shape is symmetric; persisted
 * canonically into {@code partner_fee_schedule.tier_table_json} (V018).
 *
 * <ul>
 *   <li>{@code fromVolumeUsd} — band lower bound (inclusive), major USD units,
 *       decimal STRING on the wire per {@code docs/MONEY_CONVENTION.md};
 *       NUMERIC(19,4) shape. Bands must be strictly ascending within one row
 *       (config-registry enforces).</li>
 *   <li>{@code bpsOverride} — basis points applied within the band;
 *       NUMERIC(7,4) shape (&le; 999.9999 bps), decimal STRING on the
 *       wire.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FeeTier(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fromVolumeUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bpsOverride) {
}
