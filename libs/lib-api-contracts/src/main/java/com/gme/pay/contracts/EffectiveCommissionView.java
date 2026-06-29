package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * The RESOLVED commission split for a concrete (scheme × partner × direction),
 * combining both configurable sides (V031) under a documented precedence. This
 * is the single read shape settlement / revenue-ledger / payment-executor bind
 * to instead of re-implementing the wildcard precedence themselves.
 *
 * <p>There is <b>no fixed share</b>: every field below is resolved from the
 * configured rows.
 *
 * <h2>Precedence (most-specific row wins; rows are deduped per key by config)</h2>
 *
 * <ul>
 *   <li><b>Scheme side</b> ({@code scheme_commission_share}): the row whose
 *       {@code direction} equals the queried direction wins; else the
 *       {@code direction = NULL} wildcard row; else none.</li>
 *   <li><b>Partner side</b> ({@code partner_commission_share}): among rows
 *       applicable to the query (a row applies when its non-null
 *       {@code schemeId}/{@code direction} match), the most specific wins —
 *       specificity = (schemeId set ? 2 : 0) + (direction set ? 1 : 0), giving
 *       the strict order (scheme,dir) &gt; (scheme,*) &gt; (*,dir) &gt; (*,*).</li>
 * </ul>
 *
 * <p>A {@code null} share means "no row configured on that side" — the caller
 * decides the fallback (e.g. GME keeps 100% of its cut when
 * {@code partnerSharePct} is null). {@code resolved} is {@code true} only when
 * BOTH a scheme share and a partner share were found.
 *
 * <ul>
 *   <li>{@code gmeSharePct} / {@code vanFeePct} — resolved scheme-side values
 *       (decimal STRING; {@code null} when no scheme row).</li>
 *   <li>{@code partnerSharePct} — resolved partner-side share (decimal STRING;
 *       {@code null} when no partner row).</li>
 *   <li>{@code schemeShareSource} / {@code partnerShareSource} — provenance
 *       tags for audit/debug, e.g. {@code "ZEROPAY:BOTH"}, {@code "ZEROPAY:*"},
 *       {@code "GMEREMIT:ZEROPAY:OUTBOUND"}, {@code "GMEREMIT:*:*"},
 *       or {@code "none"}.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EffectiveCommissionView(
        String schemeId,
        String partnerCode,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal gmeSharePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal vanFeePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal partnerSharePct,
        boolean resolved,
        String schemeShareSource,
        String partnerShareSource) {
}
