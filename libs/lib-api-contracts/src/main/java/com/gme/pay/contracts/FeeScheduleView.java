package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Canonical read DTO for one partner fee-schedule row
 * ({@code partner_fee_schedule}, Slice 6 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6 — Commercial Terms"). The JSON
 * shape every consumer of config-registry's fee endpoints binds to, mirroring
 * how {@link BankAccountView} is the single read shape for its multi-row
 * aggregate.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the ROW: under SCD-6 every step-6
 *       bulk replace mints fresh rows, so ids change across saves (audit
 *       reference, not a stable identifier).</li>
 *   <li>{@code schemeId} / {@code direction} — the (scheme × direction) this
 *       row prices; {@code null} = applies to all (partner-wide default).
 *       Direction roster: {@code INBOUND} | {@code OUTBOUND} | {@code BOTH}
 *       (the V017/V018 CHECK).</li>
 *   <li>{@code fixedFeeUsd} — flat per-transaction fee, major USD units,
 *       decimal STRING on the wire per {@code docs/MONEY_CONVENTION.md};
 *       scale-4 normalized server-side (NUMERIC(19,4)).</li>
 *   <li>{@code bpsFee} — variable fee in basis points (NUMERIC(7,4)), decimal
 *       STRING on the wire.</li>
 *   <li>{@code tiers} — optional volume bands overriding {@code bpsFee} from
 *       {@code fromVolumeUsd} upward, ascending; {@code null} = flat
 *       pricing.</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time of this row
 *       version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI relies on field presence, same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FeeScheduleView(
        Long id,
        String schemeId,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fixedFeeUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bpsFee,
        List<FeeTier> tiers,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
