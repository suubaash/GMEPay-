package com.gme.pay.router.resolve;

import com.gme.pay.contracts.PartnerSchemeView;
import java.util.Locale;

/**
 * One enabled {@code partner_scheme} row (config-registry V022), projected to
 * exactly the columns data-driven location + QR-network resolution needs. A thin,
 * router-owned read model over {@link com.gme.pay.contracts.PartnerSchemeView} so
 * the resolver never depends on the wire DTO's nullable-everything contract.
 *
 * @param schemeId          V022 scheme roster id (e.g. {@code ZEROPAY}).
 * @param countryCode       ISO-3166 alpha-2 of the operating location, uppercased.
 * @param direction         scheme direction: {@code INBOUND} | {@code OUTBOUND} | {@code BOTH}.
 * @param cpmSupported      true when the row is wired for customer-presented mode
 *                          (its {@code approvalMethodCpm} is populated).
 * @param mpmSupported      true when the row is wired for merchant-presented mode
 *                          (its {@code approvalMethodMpm} is populated).
 * @param priority          lower = preferred; ties broken by encounter order.
 * @param partnerId         V003/V004 partner surrogate this row belongs to; nullable
 *                          (fixture rows without a surrogate). Carried so the
 *                          ADR-016 candidate list can echo it in the response view.
 * @param networkIdentifier ADR-016 QR-network membership: a COMMA-SEPARATED GUID list
 *                          (e.g. {@code fonepay.com,nepalpay}) of the QR networks this
 *                          row serves; nullable when the row predates the column.
 */
public record PartnerSchemeRecord(
        String schemeId,
        String countryCode,
        String direction,
        boolean cpmSupported,
        boolean mpmSupported,
        int priority,
        Long partnerId,
        String networkIdentifier) {

    public PartnerSchemeRecord {
        if (schemeId == null || schemeId.isBlank()) {
            throw new IllegalArgumentException("schemeId required");
        }
        countryCode = countryCode == null ? null : countryCode.trim().toUpperCase(Locale.ROOT);
        direction = direction == null ? "BOTH" : direction.trim().toUpperCase(Locale.ROOT);
        networkIdentifier = networkIdentifier == null ? null : networkIdentifier.trim();
    }

    /**
     * Pre-ADR-016 6-arg shape (no partner surrogate / network membership).
     * Delegates {@code partnerId} + {@code networkIdentifier} to {@code null} so
     * existing country-only resolution call sites keep compiling unchanged.
     */
    public PartnerSchemeRecord(
            String schemeId,
            String countryCode,
            String direction,
            boolean cpmSupported,
            boolean mpmSupported,
            int priority) {
        this(schemeId, countryCode, direction, cpmSupported, mpmSupported, priority, null, null);
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

    /**
     * True when the requested QR network GUID is a member of this row's
     * {@code networkIdentifier} CSV (case-insensitive, whitespace-trimmed per
     * element). A row with no {@code networkIdentifier} serves no network and is
     * never a QR-classified candidate. This is the ADR-016 CSV-membership match:
     * a partner fronting several networks (e.g. {@code fonepay.com,nepalpay}) is a
     * candidate for a scan classified to any one of them.
     */
    boolean servesNetwork(String network) {
        if (network == null || network.isBlank() || networkIdentifier == null) {
            return false;
        }
        String wanted = network.trim();
        for (String token : networkIdentifier.split(",")) {
            if (token.trim().equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reconstitute the canonical wire view for the ADR-016 candidate response.
     * Only the fields the resolver read are populated; the ZeroPay-wiring /
     * approval-method columns are not carried, so they surface as {@code null}
     * (the view is {@code @JsonInclude(ALWAYS)}).
     */
    PartnerSchemeView toView() {
        return new PartnerSchemeView(
                partnerId,
                schemeId,
                direction,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Boolean.TRUE,
                countryCode,
                cpmSupported,
                mpmSupported,
                priority,
                "ACTIVE",
                networkIdentifier);
    }
}
