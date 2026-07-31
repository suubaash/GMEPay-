package com.gme.pay.payment.domain;

/**
 * Raised when the resolved scheme has no round-trip for the requested operation — today only
 * cancel/refund on the single-shot cross-border schemes (NEPAL: submit = authorize+commit;
 * SENDMN: Confirm is authorize+commit, the scheme documents no cancel).
 *
 * <p>Gap T2-7: before this existed, {@code cancelPayment} carried no scheme code, so a Nepal/SendMN
 * cancel silently fell through to {@code POST /internal/scheme/zeropay/cancel} and came back as a
 * ZeroPay decline — an unrelated scheme answering for a corridor it knows nothing about. The cancel is
 * now scheme-routed ({@code SchemeClient.CancelRequest}) and an unsupported scheme raises THIS, which
 * surfaces as a structured, non-retryable {@link #CODE} error rather than a foreign decline.
 *
 * <p>Carries a STABLE canonical error code emitted verbatim on the wire via {@code ApiError(code, …)}
 * — lib-errors is frozen, so this is not (yet) an {@code ErrorCode} enum member. Same pattern as
 * {@link OperationalGateException}.
 *
 * <p>It extends {@link PaymentException} so existing {@code catch (PaymentException)} / failover call
 * sites keep treating it as a payment-layer failure, but it is deliberately NOT a
 * {@code SchemeDeclinedException} (no scheme decision was made) and NOT a
 * {@code SchemeTimeoutException} (retrying will never succeed — nothing to fail over to).
 */
public class SchemeOperationNotSupportedException extends PaymentException {

    /** Stable canonical error code surfaced to callers. */
    public static final String CODE = "SCHEME_OPERATION_UNSUPPORTED";

    private final String schemeId;
    private final String operation;

    public SchemeOperationNotSupportedException(String schemeId, String operation, String detail) {
        super("scheme " + schemeId + " does not support " + operation
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.schemeId = schemeId;
        this.operation = operation;
    }

    /** The scheme CODE that cannot perform the operation (e.g. {@code "SENDMN"}). */
    public String schemeId() {
        return schemeId;
    }

    /** The unsupported operation (e.g. {@code "cancelPayment"}). */
    public String operation() {
        return operation;
    }

    /** The stable canonical error code ({@link #CODE}). */
    public String code() {
        return CODE;
    }
}
