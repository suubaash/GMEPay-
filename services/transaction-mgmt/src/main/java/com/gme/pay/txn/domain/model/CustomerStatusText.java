package com.gme.pay.txn.domain.model;

import java.util.Map;

/**
 * Plain-language, customer-support-facing text mapping (CS quick-wins).
 *
 * <p>Two responsibilities, both pure functions:
 * <ul>
 *   <li>{@link #statusLabel(TransactionStatus)} — a human-readable label for an FSM
 *       {@link TransactionStatus} (e.g. {@code APPROVED} → "Payment approved").</li>
 *   <li>{@link #declineReasonText(String)} — a customer-friendly sentence for an internal
 *       {@code failureReason} code (e.g. {@code APPROVAL_TIMEOUT} → "The payment timed out
 *       waiting for the payment network to confirm."), falling back to the raw reason for an
 *       unmapped code.</li>
 * </ul>
 *
 * <p>Null-safe throughout: a null status or reason yields null (so
 * {@code @JsonInclude(NON_NULL)} omits the field).
 */
public final class CustomerStatusText {

    private CustomerStatusText() {}

    /** FSM state → plain-language label shown to a support agent / customer. */
    private static final Map<TransactionStatus, String> STATUS_LABELS = Map.of(
            TransactionStatus.CREATED,       "Payment created",
            TransactionStatus.PENDING_DEBIT, "Awaiting debit",
            TransactionStatus.SCHEME_SENT,   "Sent to scheme, awaiting confirmation",
            TransactionStatus.APPROVED,      "Payment approved",
            TransactionStatus.UNCERTAIN,     "Pending verification",
            TransactionStatus.FAILED,        "Declined",
            TransactionStatus.CANCELLED,     "Cancelled",
            TransactionStatus.REVERSED,      "Reversed / refunded",
            TransactionStatus.REFUNDED,      "Refunded");

    /**
     * Internal {@code failureReason} code → customer-friendly sentence. Codes are the values the
     * FAILED paths record (OI-01 sweeper, scheme rejects). An unmapped / free-text reason falls
     * back to the raw string via {@link #declineReasonText}.
     */
    private static final Map<String, String> DECLINE_REASONS = Map.of(
            "APPROVAL_TIMEOUT",
            "The payment timed out waiting for the payment network to confirm.",
            "SCHEME_REJECTED",
            "The payment was declined by the payment network.",
            "INSUFFICIENT_PREFUNDING",
            "The payment could not be funded and was declined.",
            "TTL_EXPIRED",
            "The payment request expired before it could be completed.",
            "EXPIRED",
            "The payment request expired before it could be completed.");

    /** Human-readable label for a status; null when {@code status} is null. */
    public static String statusLabel(TransactionStatus status) {
        if (status == null) return null;
        return STATUS_LABELS.getOrDefault(status, status.name());
    }

    /**
     * Customer-friendly decline sentence for an internal failure-reason code. Returns null when
     * {@code failureReason} is null/blank; falls back to the raw reason when the code is unmapped
     * (so support still sees SOMETHING rather than nothing).
     */
    public static String declineReasonText(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) return null;
        return DECLINE_REASONS.getOrDefault(failureReason, failureReason);
    }
}
