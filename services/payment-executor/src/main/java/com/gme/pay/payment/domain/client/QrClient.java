package com.gme.pay.payment.domain.client;

/**
 * Interface to the QR Service (qr-service).
 * Resolves a scanned QR string to merchant details.
 */
public interface QrClient {

    /**
     * Parses and resolves a CPM or MPM QR code to a merchant.
     *
     * @param merchantQr the raw QR string scanned by the customer
     * @return resolved merchant info
     * @throws com.gme.pay.payment.domain.PaymentException if the QR is invalid or merchant inactive
     */
    MerchantView resolve(String merchantQr);

    /** Immutable view of a resolved QR merchant. */
    record MerchantView(String merchantId, String merchantName, String payoutCurrency, String schemeId, boolean active) {

        /**
         * Backwards-compatible 4-arg factory: treats merchant as active (legacy callers
         * that do not supply an active/status field).
         */
        public static MerchantView of(String merchantId, String merchantName,
                                      String payoutCurrency, String schemeId) {
            return new MerchantView(merchantId, merchantName, payoutCurrency, schemeId, true);
        }
    }
}
