package com.gme.pay.scheme.sendmn.adapter;

/**
 * Maps SendMN {@code PAYMENT_STATUS} values to the canonical adapter states.
 *
 * <p>Documented statuses: {@code Decrypted} (QR verified, payment not yet made),
 * {@code Processing} (in flight), {@code Approved} (confirmed). The doc defines NO
 * failed/declined terminal status (open issue O4), so everything unrecognised —
 * including null (poll failed / timed out) — maps to {@code UNKNOWN}, never to a
 * failure state (ADR-016 anti-double-charge: only a human or a definitive scheme
 * answer may fail a payment).</p>
 */
public final class SendmnStatusMapper {

    public static final String APPROVED = "APPROVED";
    public static final String PENDING = "PENDING";
    public static final String UNKNOWN = "UNKNOWN";

    private SendmnStatusMapper() {
    }

    /** Canonical state for a raw SendMN {@code PAYMENT_STATUS} (case-insensitive). */
    public static String toCanonical(String paymentStatus) {
        if (paymentStatus == null) return UNKNOWN;
        return switch (paymentStatus.trim().toLowerCase()) {
            case "decrypted", "processing" -> PENDING;
            case "approved" -> APPROVED;
            default -> UNKNOWN;
        };
    }
}
