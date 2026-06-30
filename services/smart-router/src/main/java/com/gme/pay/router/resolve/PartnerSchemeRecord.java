package com.gme.pay.router.resolve;

import java.util.Locale;

/**
 * One enabled {@code partner_scheme} row (config-registry V022), projected to
 * exactly the columns data-driven location resolution needs. A thin, router-
 * owned read model over {@link com.gme.pay.contracts.PartnerSchemeView} so the
 * resolver never depends on the wire DTO's nullable-everything contract.
 *
 * @param schemeId          V022 scheme roster id (e.g. {@code ZEROPAY}).
 * @param countryCode       ISO-3166 alpha-2 of the operating location, uppercased.
 * @param direction         scheme direction: {@code INBOUND} | {@code OUTBOUND} | {@code BOTH}.
 * @param cpmSupported      true when the row is wired for customer-presented mode
 *                          (its {@code approvalMethodCpm} is populated).
 * @param mpmSupported      true when the row is wired for merchant-presented mode
 *                          (its {@code approvalMethodMpm} is populated).
 * @param priority          lower = preferred; ties broken by encounter order.
 */
public record PartnerSchemeRecord(
        String schemeId,
        String countryCode,
        String direction,
        boolean cpmSupported,
        boolean mpmSupported,
        int priority) {

    public PartnerSchemeRecord {
        if (schemeId == null || schemeId.isBlank()) {
            throw new IllegalArgumentException("schemeId required");
        }
        countryCode = countryCode == null ? null : countryCode.trim().toUpperCase(Locale.ROOT);
        direction = direction == null ? "BOTH" : direction.trim().toUpperCase(Locale.ROOT);
    }

    /** True when this row participates in the requested transaction direction. */
    boolean enabledFor(String requestedDirection) {
        if ("BOTH".equals(direction)) {
            return true;
        }
        return direction.equals(requestedDirection);
    }

    /** True when this row is wired for the requested presentment mode. */
    boolean supports(PaymentMode mode) {
        return mode == PaymentMode.CPM ? cpmSupported : mpmSupported;
    }
}
