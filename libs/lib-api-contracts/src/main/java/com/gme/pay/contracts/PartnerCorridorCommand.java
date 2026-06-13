package com.gme.pay.contracts;

import java.time.LocalDate;

/**
 * Canonical write payload for ONE corridor on the wizard's step-7 corridor
 * matrix builder (Slice 7 — Schemes &amp; Corridors, see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 7"). Corridors ride
 * {@link PartnerCommand.UpdateStep7Corridors} as a list — bulk-replace
 * semantics, the same contract as {@link RuleCommand} /
 * {@link BankAccountCommand}.
 *
 * <p>A corridor is keyed by (partner × {@code srcCountry} × {@code srcCcy} ×
 * {@code dstCountry} × {@code dstCcy}); the partner is identified by the URL,
 * so this payload carries only the lane half of the key plus the lifecycle
 * fields.
 *
 * <ul>
 *   <li>{@code srcCountry} / {@code dstCountry} — required ISO-3166 alpha-2
 *       country codes, UPPERCASE (e.g. {@code KR}, {@code MN}). CHAR(2) in
 *       storage (V023) — config-registry validates the format.</li>
 *   <li>{@code srcCcy} / {@code dstCcy} — required ISO-4217 currency codes,
 *       UPPERCASE (e.g. {@code KRW}, {@code MNT}). CHAR(3) in storage.</li>
 *   <li>{@code goLiveDate} — when the corridor opens for live traffic
 *       (ISO-8601 {@code yyyy-MM-dd} on the wire); {@code null} = not yet
 *       scheduled.</li>
 *   <li>{@code isActive} — corridor toggle; transactions on an inactive
 *       corridor are rejected at the gateway (Slice 7 exit gate).
 *       {@code null} defaults to {@code true} (the V023 column default).</li>
 *   <li>{@code strEnabled} — per-corridor KoFIU Suspicious Transaction
 *       Reporting switch (V029.1, Slice 8 Lane C); {@code null} defaults to
 *       {@code false} (the column default — STR feeds are enabled
 *       lane-by-lane as counterparty FIU integrations go live).</li>
 * </ul>
 */
public record PartnerCorridorCommand(
        String srcCountry,
        String srcCcy,
        String dstCountry,
        String dstCcy,
        LocalDate goLiveDate,
        Boolean isActive,
        Boolean strEnabled) {

    /**
     * Pre-Slice-8 arity ({@code strEnabled} omitted → {@code null}, server
     * defaults it to {@code false}) — keeps Slice 7 callers source-compatible.
     */
    public PartnerCorridorCommand(String srcCountry, String srcCcy,
                                  String dstCountry, String dstCcy,
                                  LocalDate goLiveDate, Boolean isActive) {
        this(srcCountry, srcCcy, dstCountry, dstCcy, goLiveDate, isActive, null);
    }
}
