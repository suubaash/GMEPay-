package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical read DTO for one partner-side commission-share row
 * ({@code partner_commission_share}, V031) — how GME's commission (its cut of
 * the net merchant fee, after the scheme split) is shared with the wallet
 * partner. Configured in "wallet partner setup" (Slice 6 — Commercial Terms).
 *
 * <p>There is <b>no fixed commission share</b>: every partner can carry its own
 * configurable {@code partnerSharePct}.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the ROW; SCD-6 mints fresh rows on
 *       every save (audit reference, not a stable identifier).</li>
 *   <li>{@code schemeId} / {@code direction} — the (scheme × direction) this
 *       row prices; {@code null} = applies to all (partner-wide default).
 *       Direction roster: {@code INBOUND} | {@code OUTBOUND} | {@code BOTH}.</li>
 *   <li>{@code partnerSharePct} — the partner's fraction of GME's commission,
 *       decimal STRING on the wire (NUMERIC(6,4), in {@code [0,1]}); GME keeps
 *       the remainder {@code 1 - partnerSharePct}.</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * same contract as {@link FeeScheduleView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PartnerCommissionShareView(
        Long id,
        String schemeId,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal partnerSharePct,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
