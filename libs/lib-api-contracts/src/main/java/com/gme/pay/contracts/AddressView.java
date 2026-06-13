package com.gme.pay.contracts;

/**
 * Structured postal address — the read shape used by every consumer that needs
 * to display a partner's registered or operating address. Mirrors the schema
 * the Slice 1 plan introduces on the {@code partner} aggregate
 * ({@code docs/PARTNER_SETUP_PLAN.md} §"Slice 1 — Identity + Foundation"):
 *
 * <ul>
 *   <li>{@code street1}, {@code street2} — building / unit lines. {@code street2}
 *       is optional; pass {@code null} when not used.</li>
 *   <li>{@code city} — locality.</li>
 *   <li>{@code state} — province / state / 시·도. Optional for jurisdictions that
 *       do not use a sub-national label.</li>
 *   <li>{@code postcode} — postal code as a free-form string (zero-padded values
 *       must arrive as strings to survive JSON serialisation).</li>
 *   <li>{@code country} — ISO-3166 alpha-2 country code (e.g. {@code "KR"},
 *       {@code "KH"}, {@code "SG"}).</li>
 * </ul>
 *
 * <p>The companion write DTO is {@link AddressCommand}. The two shapes are
 * intentionally identical for Slice 1; if validation rules ever need to differ
 * (e.g. command requires non-null fields the view tolerates as null on partly
 * populated drafts) the split is already in place.
 */
public record AddressView(
        String street1,
        String street2,
        String city,
        String state,
        String postcode,
        String country) {
}
