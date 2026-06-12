package com.gme.pay.contracts;

import java.time.LocalDate;

/**
 * Canonical write payload for a partner's commercial contract (Slice 6 —
 * Commercial Terms, see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 6"). Rides
 * inside {@link PartnerCommand.UpdateStep6Commercial#contract()} — full-state
 * replace of the {@code partner_contract} row (SCD-6 paired write per
 * ADR-010), the same single-row discipline as {@link FxConfigCommand}. The
 * read shape is {@link ContractView}.
 *
 * <ul>
 *   <li>{@code effectiveFrom} — required; the contract term start
 *       (ISO-8601 date on the wire, e.g. {@code "2026-07-01"}).</li>
 *   <li>{@code effectiveTo} — optional; must not be before
 *       {@code effectiveFrom} when present (a one-day contract is legal);
 *       {@code null} = open-ended / evergreen.</li>
 *   <li>{@code autoRenewal} — {@code null} defaults to {@code false}.</li>
 *   <li>{@code noticePeriodDays} — optional; &ge; 0 when present.</li>
 *   <li>{@code refundChargebackPolicy} — optional; {@code PARTNER_BEARS} |
 *       {@code MERCHANT_BEARS} | {@code SHARED} (the V021 CHECK roster) when
 *       present.</li>
 *   <li>{@code terminationReason} — optional, &le; 200 chars; normally set by
 *       the Slice 8 lifecycle flow, accepted here so a terminated-and-renewed
 *       partner's history can be backfilled during onboarding.</li>
 * </ul>
 */
public record ContractCommand(
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Boolean autoRenewal,
        Integer noticePeriodDays,
        String refundChargebackPolicy,
        String terminationReason) {
}
