package com.gme.pay.merchant.domain;

/**
 * Merchant domain record returned by the merchant lookup API.
 *
 * <p>Fields match the ZeroPay canonical merchant projection used at payment time.
 * {@code qrCodeId} is the CHAR(20) ZeroPay QR code identifier used as the lookup key.
 *
 * <p>UC-07-03 fields: {@code payoutCurrency} and {@code schemeId} are required by
 * payment-executor's {@code RestQrClient} contract. {@code city} and {@code mcc}
 * (Merchant Category Code) support fee-rate classification.
 */
public record Merchant(
        String merchantId,
        String qrCodeId,
        /** Human-readable merchant name (serialised as {@code merchantName} in the REST layer). */
        String name,
        String merchantType,
        String feeType,
        String status,
        boolean active,
        /** ISO 4217 payout currency (e.g. {@code KRW}). Required by RestQrClient. */
        String payoutCurrency,
        /** Scheme identifier routing payments (e.g. {@code ZEROPAY}). Required by RestQrClient. */
        String schemeId,
        /** City / locality of the merchant outlet. */
        String city,
        /** ISO 18245 Merchant Category Code (4-digit string, e.g. {@code 5411}). */
        String mcc) {

    /** Convenience check — true only when status is ACTIVE and the active flag is set. */
    public boolean isOperational() {
        return active && "ACTIVE".equalsIgnoreCase(status);
    }

    /** Backwards-compatible constructor for tests and seeds that don't set UC-07-03 fields. */
    public Merchant(String merchantId, String qrCodeId, String name,
                    String merchantType, String feeType, String status, boolean active) {
        this(merchantId, qrCodeId, name, merchantType, feeType, status, active,
                null, null, null, null);
    }
}
