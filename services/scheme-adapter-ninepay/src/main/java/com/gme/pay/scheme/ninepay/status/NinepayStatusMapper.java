package com.gme.pay.scheme.ninepay.status;

import java.util.Locale;

/**
 * Maps 9Pay wire statuses and IPN message codes onto the canonical {@link PayoutStatus}.
 *
 * <p>Two independent signals arrive on the wire:</p>
 * <ul>
 *   <li>{@code status} (transfer / transfer-info / IPN): PENDING | PROCESSING | SUCCESS | FAIL.</li>
 *   <li>IPN {@code code} (Str 3, section 6/7 of the spec): 000 success, 001–007 bank-side
 *       failure reasons (004 = "not yet processed", retryable with the same data), 008 =
 *       held by 9Pay pending merchant confirmation, 009 = bank reversal AFTER success.</li>
 * </ul>
 *
 * <p>The IPN code, when present, is the more specific signal and wins over the status
 * field (in particular 008/009 arrive alongside a status that would otherwise look
 * final).</p>
 */
public final class NinepayStatusMapper {

    /** IPN message code: successful transaction. */
    public static final String CODE_SUCCESS = "000";
    /** IPN message code: not yet processed by bank — retryable with the same data. */
    public static final String CODE_NOT_YET_PROCESSED = "004";
    /** IPN message code: held by 9Pay pending merchant confirmation. */
    public static final String CODE_HELD = "008";
    /** IPN message code: bank reversal — payment reversed AFTER the fact. */
    public static final String CODE_REVERSED = "009";

    private NinepayStatusMapper() {
    }

    /**
     * Maps the 9Pay {@code status} field. Unrecognised values map to {@link PayoutStatus#UNKNOWN}
     * — never auto-fail on a status we do not understand.
     */
    public static PayoutStatus fromSchemeStatus(String status) {
        if (status == null) {
            return PayoutStatus.UNKNOWN;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "PENDING" -> PayoutStatus.PENDING;
            case "PROCESSING" -> PayoutStatus.PROCESSING;
            case "SUCCESS" -> PayoutStatus.SUCCESS;
            case "FAIL", "FAILED" -> PayoutStatus.FAILED;
            default -> PayoutStatus.UNKNOWN;
        };
    }

    /**
     * Maps an IPN {@code code} (000–009), falling back to {@link #fromSchemeStatus} when the
     * code is absent (the code field is optional and NOT part of the IPN signed string).
     */
    public static PayoutStatus fromIpn(String code, String status) {
        if (code == null || code.isBlank()) {
            return fromSchemeStatus(status);
        }
        return switch (code.trim()) {
            case CODE_SUCCESS -> PayoutStatus.SUCCESS;
            case CODE_NOT_YET_PROCESSED -> PayoutStatus.PENDING; // retryable, not final
            case CODE_HELD -> PayoutStatus.HELD;
            case CODE_REVERSED -> PayoutStatus.REVERSED;
            case "001", "002", "003", "005", "006", "007" -> PayoutStatus.FAILED;
            default -> fromSchemeStatus(status);
        };
    }
}
