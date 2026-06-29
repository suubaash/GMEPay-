package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical read DTO for one scheme-side commission-share row
 * ({@code scheme_commission_share}, V031) — how the NET merchant fee on a QR
 * transaction is split between GME and the scheme operator. Configured in
 * "QR scheme setup"; the read shape every consumer of config-registry's scheme
 * commission endpoints binds to.
 *
 * <p>There is <b>no fixed commission share</b>: ZeroPay's historical 70/30 is
 * just one configurable value of {@code gmeSharePct}.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the ROW; under SCD-6 every save
 *       mints fresh rows, so ids change across saves (audit reference, not a
 *       stable identifier).</li>
 *   <li>{@code schemeId} — the scheme CODE this row prices (e.g.
 *       {@code "ZEROPAY"}).</li>
 *   <li>{@code direction} — {@code INBOUND} | {@code OUTBOUND} | {@code BOTH};
 *       {@code null} = applies to all directions (scheme-wide default).</li>
 *   <li>{@code gmeSharePct} — GME's fraction of the net merchant fee, decimal
 *       STRING on the wire (NUMERIC(6,4), in {@code (0,1]}); the scheme keeps
 *       the remainder {@code 1 - gmeSharePct}.</li>
 *   <li>{@code vanFeePct} — VAN intermediary rate deducted from the GROSS
 *       merchant fee before the split (NUMERIC(7,4), {@code >= 0}); decimal
 *       STRING on the wire.</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * same contract as {@link FeeScheduleView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SchemeCommissionShareView(
        Long id,
        String schemeId,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal gmeSharePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal vanFeePct,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
