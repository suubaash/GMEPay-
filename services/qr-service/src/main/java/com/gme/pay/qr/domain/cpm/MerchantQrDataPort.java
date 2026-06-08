package com.gme.pay.qr.domain.cpm;

/**
 * Anti-corruption port for the merchant-qr-data service (MSA rule: call via API, never import
 * the other service's entities).
 *
 * <p>Implementations will call {@code GET /v1/merchants/{qrCodeId}} over HTTP; a WireMock stub
 * is used in tests.
 */
public interface MerchantQrDataPort {

    /**
     * Resolve a merchant record from its QR code identifier.
     *
     * @param qrCodeId the {@code qr_code_id} extracted from the parsed QR payload
     * @return merchant resolution result, never null
     * @throws com.gme.pay.qr.exception.MerchantNotFoundException if the merchant-qr-data service
     *                                                             returns 404
     */
    MerchantResolution resolve(String qrCodeId);

    /** Minimal data needed from the merchant-qr-data service. */
    record MerchantResolution(String merchantId, String merchantName,
                              String schemeId,   boolean active) {}
}
