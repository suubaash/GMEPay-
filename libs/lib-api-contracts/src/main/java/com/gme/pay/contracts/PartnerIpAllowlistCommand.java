package com.gme.pay.contracts;

/**
 * One CIDR entry of the wizard's step-8 IP-allowlist editor (Slice 8 Lane B —
 * see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 8"). Rides
 * {@link PartnerCommand.UpdateStep8Credentials} as a <b>bulk replace</b>: the
 * payload carries the FULL desired allowlist across both environments.
 *
 * <ul>
 *   <li>{@code cidr} — required; IPv4 ({@code a.b.c.d/0..32}) or IPv6
 *       ({@code hex-groups/0..128}) range in CIDR notation, &le; 43 chars
 *       (V026). Full shape validation is config-registry service-layer.</li>
 *   <li>{@code label} — optional operator-facing note, &le; 120 chars.</li>
 *   <li>{@code environment} — required; {@code SANDBOX} | {@code PRODUCTION}
 *       (the V026 CHECK roster). String per the {@code legalForm} /
 *       {@code settlementMethod} precedent — config-registry validates the
 *       roster. Hard ceiling: 10 entries per (partner, environment);
 *       exceeding it is a 409 {@code CIDR_LIMIT_EXCEEDED}.</li>
 * </ul>
 *
 * <p>The read shape is {@link PartnerIpAllowlistView}.
 */
public record PartnerIpAllowlistCommand(
        String cidr,
        String label,
        String environment) {
}
