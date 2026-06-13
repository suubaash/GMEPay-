package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * Canonical read DTO for one persisted corridor ({@code partner_corridor},
 * V023) — Slice 7 (Schemes &amp; Corridors). The write side is
 * {@link PartnerCorridorCommand} (one element of
 * {@link PartnerCommand.UpdateStep7Corridors}).
 *
 * <ul>
 *   <li>{@code partnerId} — the partner's BIGINT surrogate (V003/V004). The
 *       URL already names the partner by business code; the surrogate is
 *       echoed so downstream consumers (SchemeRouter, gateway corridor gate)
 *       can join without a second lookup.</li>
 *   <li>{@code srcCountry} / {@code srcCcy} / {@code dstCountry} /
 *       {@code dstCcy} — the corridor key alongside the partner. ISO-3166
 *       alpha-2 / ISO-4217, UPPERCASE.</li>
 *   <li>{@code goLiveDate} — when the corridor opens for live traffic
 *       (ISO-8601 {@code yyyy-MM-dd} on the wire); {@code null} = not yet
 *       scheduled.</li>
 *   <li>{@code isActive} — corridor toggle; never {@code null} on reads (the
 *       V023 column is NOT NULL with DEFAULT TRUE).</li>
 *   <li>{@code strEnabled} — per-corridor KoFIU Suspicious Transaction
 *       Reporting switch (V029.1, Slice 8 Lane C); never {@code null} on
 *       reads (the column is NOT NULL with DEFAULT FALSE).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * same contract as {@link PartnerView} / {@link RuleView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PartnerCorridorView(
        Long partnerId,
        String srcCountry,
        String srcCcy,
        String dstCountry,
        String dstCcy,
        LocalDate goLiveDate,
        Boolean isActive,
        Boolean strEnabled) {

    /**
     * Pre-Slice-8 arity ({@code strEnabled} → {@code false}, mirroring the
     * V029.1 column default) — keeps Slice 7 callers (including the BFF stub)
     * source-compatible.
     */
    public PartnerCorridorView(Long partnerId, String srcCountry, String srcCcy,
                               String dstCountry, String dstCcy,
                               LocalDate goLiveDate, Boolean isActive) {
        this(partnerId, srcCountry, srcCcy, dstCountry, dstCcy,
                goLiveDate, isActive, Boolean.FALSE);
    }
}
