package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Canonical read DTO for a partner's commercial contract
 * ({@code partner_contract}, Slice 6 — see {@code docs/PARTNER_SETUP_PLAN.md}
 * §"Slice 6 — Commercial Terms"). The JSON shape every consumer of
 * config-registry's contract endpoints binds to.
 *
 * <ul>
 *   <li>{@code id} — BIGSERIAL surrogate of the ROW: under SCD-6 every step-6
 *       save mints a fresh row (audit reference, not a stable
 *       identifier).</li>
 *   <li>{@code effectiveFrom} / {@code effectiveTo} — the commercial contract
 *       TERM (DATE-granular; signed paper carries dates, not instants).
 *       {@code effectiveTo} {@code null} = open-ended / evergreen. NOT the
 *       ADR-010 business-time axis — that is {@code validFrom} /
 *       {@code validTo} below; an amendment moving the end date produces a
 *       new row version whose {@code effectiveTo} differs.</li>
 *   <li>{@code autoRenewal} / {@code noticePeriodDays} — evergreen renewal at
 *       {@code effectiveTo} and how many days before it either side must give
 *       notice to break it.</li>
 *   <li>{@code refundChargebackPolicy} — {@code PARTNER_BEARS} |
 *       {@code MERCHANT_BEARS} | {@code SHARED} (V021 CHECK); String per the
 *       {@code riskRating} precedent.</li>
 *   <li>{@code terminationReason} — populated by the Slice 8 lifecycle flow;
 *       carried here so the contract row is self-describing in history.</li>
 *   <li>Bitemporal stamps (ADR-010): {@code validFrom} / {@code validTo}
 *       (business time) and {@code recordedAt} (transaction time of this row
 *       version).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} so {@code null} fields stay on the wire —
 * the UI relies on field presence, same contract as {@link PartnerView}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ContractView(
        Long id,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Boolean autoRenewal,
        Integer noticePeriodDays,
        String refundChargebackPolicy,
        String terminationReason,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt) {
}
