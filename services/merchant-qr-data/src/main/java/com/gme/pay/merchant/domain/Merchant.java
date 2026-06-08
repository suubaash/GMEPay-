package com.gme.pay.merchant.domain;

/**
 * Merchant DTO returned by the merchant lookup API.
 *
 * <p>Fields match the ZeroPay canonical merchant projection used at payment time.
 * {@code qrCodeId} is the CHAR(20) ZeroPay QR code identifier used as the lookup key.
 */
public record Merchant(
        String merchantId,
        String qrCodeId,
        String name,
        String merchantType,
        String feeType,
        String status,
        boolean active) {

    /** Convenience check — true only when status is ACTIVE and the active flag is set. */
    public boolean isOperational() {
        return active && "ACTIVE".equalsIgnoreCase(status);
    }
}
