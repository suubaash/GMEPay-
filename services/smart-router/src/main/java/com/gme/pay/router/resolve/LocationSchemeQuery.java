package com.gme.pay.router.resolve;

/**
 * The input to data-driven scheme-for-location resolution: WHERE the
 * transaction is (country/location), HOW the QR is presented (CPM/MPM), and
 * WHICH WAY value flows (direction). All three are part of the resolution key.
 *
 * @param countryCode ISO-3166 alpha-2 of the merchant/customer location.
 * @param mode        presentment mode (CPM / MPM).
 * @param direction   transaction direction; {@code INBOUND} | {@code OUTBOUND}
 *                    | {@code DOMESTIC} (validated by the resolver against
 *                    {@link com.gme.pay.domain.Direction}).
 */
public record LocationSchemeQuery(String countryCode, PaymentMode mode, String direction) {
}
