package com.gme.pay.scheme.ninepay.client;

/**
 * A definitive business-level rejection from 9Pay: the API answered and said "no",
 * carrying an {@code error.code} (section 5 of the digest — e.g. 1062 = duplicate
 * {@code request_id}, 1024 = insufficient balance, 1021 = transaction not exists).
 *
 * <p>Contrast {@link NinepayTransportException}: this exception means 9Pay's verdict is
 * KNOWN; the transport exception means it is NOT (and the caller must poll before any
 * retry — ADR-016-style anti-double-payout).</p>
 */
public class NinepayErrorException extends RuntimeException {

    /** Duplicate request_id — the transfer was already submitted; poll, never resubmit. */
    public static final String CODE_DUPLICATE_REQUEST_ID = "1062";
    /** Request not found. */
    public static final String CODE_REQUEST_NOT_FOUND = "1005";
    /** Transaction not exists (lookup). */
    public static final String CODE_TRANSACTION_NOT_EXISTS = "1021";

    private final String code;

    public NinepayErrorException(String code, String message) {
        super("9Pay error " + code + ": " + message);
        this.code = code == null ? "" : code;
    }

    public String code() {
        return code;
    }

    /** True when this error means "9Pay never accepted a transaction for this lookup key". */
    public boolean isNotFound() {
        return CODE_REQUEST_NOT_FOUND.equals(code) || CODE_TRANSACTION_NOT_EXISTS.equals(code);
    }

    /** True when this error means the request_id was already used (already-submitted). */
    public boolean isDuplicateRequestId() {
        return CODE_DUPLICATE_REQUEST_ID.equals(code);
    }
}
