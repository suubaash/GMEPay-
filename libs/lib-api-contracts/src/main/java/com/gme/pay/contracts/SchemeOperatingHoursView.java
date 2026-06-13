package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalTime;

/**
 * Canonical read DTO for one row of the {@code scheme_operating_hours}
 * reference table (V024) — Slice 7 (Scheme Enablement). Read-only: the table
 * is migration-seeded reference data, so there is no command counterpart.
 *
 * <ul>
 *   <li>{@code schemeId} — the V022 scheme roster identifier.</li>
 *   <li>{@code weekday} — {@code 0} = Monday .. {@code 6} = Sunday.</li>
 *   <li>{@code openTimeLocal} / {@code closeTimeLocal} — wall-clock window in
 *       {@code timezone} during which the scheme accepts traffic; 24x7 rails
 *       carry {@code 00:00:00} / {@code 23:59:59}.</li>
 *   <li>{@code cutoffTimeLocal} — daily settlement cutoff (wall-clock in
 *       {@code timezone}); {@code null} = no intra-day cutoff.</li>
 *   <li>{@code timezone} — IANA zone id the three times are evaluated in
 *       (the V013 cutoff precedent).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SchemeOperatingHoursView(
        String schemeId,
        int weekday,
        LocalTime openTimeLocal,
        LocalTime closeTimeLocal,
        LocalTime cutoffTimeLocal,
        String timezone) {
}
