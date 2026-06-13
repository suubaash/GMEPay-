package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * One ultimate beneficial owner as captured in wizard step 3 (Slice 3 — see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 3 — KYB"). Used symmetrically as
 * the read AND write element shape — UBO rows carry no server-generated
 * surrogate (they live inside {@code partner_kyb.ubo_set_jsonb} as a JSON
 * array, ADR-010-versioned with the row), so unlike contacts there is nothing
 * to add on the read side.
 *
 * <ul>
 *   <li>{@code name} — full romanized name; required, &le; 120 chars.</li>
 *   <li>{@code ownershipPct} — percentage stake, 0–100, plain decimal (never
 *       floating-point on the wire per {@code docs/MONEY_CONVENTION.md}'s
 *       BigDecimal discipline).</li>
 *   <li>{@code isPep} — operator-declared politically-exposed-person flag.
 *       {@code Boolean} (not {@code boolean}) so the write path can treat
 *       {@code null} as {@code false}, mirroring
 *       {@code ContactCommand.authorizedSignatory}.</li>
 *   <li>{@code country} — ISO-3166 alpha-2 of residence/nationality;
 *       optional.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UboView(
        String name,
        BigDecimal ownershipPct,
        Boolean isPep,
        String country) {
}
