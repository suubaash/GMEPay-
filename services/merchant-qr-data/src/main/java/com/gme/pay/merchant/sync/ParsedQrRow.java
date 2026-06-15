package com.gme.pay.merchant.sync;

/**
 * Parsed row from a ZeroPay QR-type file (ZP0043, ZP0047, ZP0053).
 *
 * <p>Immutable value object. {@code recordType} is {@code null} for full-list
 * files (ZP0053) that do not carry a change-type marker.
 *
 * @param recordType  "QR" (register), "QD" (deactivate), or {@code null} for ZP0053
 * @param qrCode      ZeroPay QR identifier (CHAR 20)
 * @param merchantId  Owning merchant identifier (CHAR 10)
 * @param status      ACTIVE | DEACTIVATED
 */
public record ParsedQrRow(
        String recordType,
        String qrCode,
        String merchantId,
        String status) {

    /** Returns {@code true} when the record type signals a QR deactivation. */
    public boolean isDeactivation() {
        return "QD".equalsIgnoreCase(recordType)
               || "DEACTIVATED".equalsIgnoreCase(status);
    }

    /** Returns {@code true} when the QR is active. */
    public boolean isActive() {
        return !isDeactivation();
    }
}
