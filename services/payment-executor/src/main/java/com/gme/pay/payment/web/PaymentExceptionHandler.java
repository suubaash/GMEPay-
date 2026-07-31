package com.gme.pay.payment.web;

import com.gme.pay.correlation.CorrelationHeaders;
import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.payment.domain.CorridorPricingUnavailableException;
import com.gme.pay.payment.domain.CumulativeLimitExceededException;
import com.gme.pay.payment.domain.InsufficientPrefundingException;
import com.gme.pay.payment.domain.LimitCheckUnavailableException;
import com.gme.pay.payment.domain.MerchantNotFoundException;
import com.gme.pay.payment.domain.OperationalGateException;
import com.gme.pay.payment.domain.PartialRefundNotSupportedException;
import com.gme.pay.payment.domain.PaymentNotFoundException;
import com.gme.pay.payment.domain.PaymentScreeningRefusedException;
import com.gme.pay.payment.domain.QuoteAmountMismatchException;
import com.gme.pay.payment.domain.RefundAmountInvalidException;
import com.gme.pay.payment.domain.SchemeBalanceUnavailableException;
import com.gme.pay.payment.domain.SchemeClosedException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeOperationNotSupportedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.payment.domain.TransactionLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.MDC;

import java.util.UUID;

/** Maps payment domain exceptions to canonical HTTP error responses. */
@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(InsufficientPrefundingException.class)
    public ResponseEntity<ApiError> handleInsufficientPrefunding(InsufficientPrefundingException ex) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(ApiError.of(ErrorCode.INSUFFICIENT_PREFUNDING,
                        ex.getMessage(), newRequestId()));
    }

    @ExceptionHandler(QuoteAmountMismatchException.class)
    public ResponseEntity<ApiError> handleQuoteAmountMismatch(QuoteAmountMismatchException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ErrorCode.QUOTE_AMOUNT_MISMATCH,
                        ex.getMessage(), newRequestId()));
    }

    @ExceptionHandler(SchemeBalanceUnavailableException.class)
    public ResponseEntity<ApiError> handleSchemeBalanceUnavailable(SchemeBalanceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(ApiError.of(ErrorCode.SCHEME_BALANCE_INSUFFICIENT,
                        ex.getMessage(), newRequestId()));
    }

    @ExceptionHandler(TransactionLimitExceededException.class)
    public ResponseEntity<ApiError> handleTransactionLimitExceeded(TransactionLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ErrorCode.TRANSACTION_LIMIT_EXCEEDED,
                        ex.getMessage(), newRequestId()));
    }

    @ExceptionHandler(CumulativeLimitExceededException.class)
    public ResponseEntity<ApiError> handleCumulativeLimitExceeded(CumulativeLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED,
                        ex.getMessage(), newRequestId()));
    }

    /**
     * T4-2: the partner HAS a regulatory limit configured but it could not be evaluated (no USD basis
     * for the corridor amount, or the cumulative-usage ledger was unreachable). We refuse rather than
     * bypass the cap, so this is a 503 with {@code retryable=true} and the stable
     * {@code LIMIT_CHECK_UNAVAILABLE} code — not a breach (422) and not a silent approval. Emitted via
     * the {@link ApiError} string ctor because lib-errors is frozen (same pattern as
     * {@code OperationalGateException}).
     */
    @ExceptionHandler(LimitCheckUnavailableException.class)
    public ResponseEntity<ApiError> handleLimitCheckUnavailable(LimitCheckUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(ex.code(), ex.getMessage(), true, newRequestId()));
    }

    /**
     * T4-1: the corridor could not be PRICED — its FX margin / service fee is not configured, or the
     * live reference rate was unavailable. 503 with the stable code carried on the exception
     * ({@code CORRIDOR_PRICING_NOT_CONFIGURED}, {@code retryable=false} — an owner must supply the
     * terms; or {@code CORRIDOR_RATE_UNAVAILABLE}, {@code retryable=true} — a transient rate outage).
     * Never a 2xx: refusing beats mispricing, and no float moved and no scheme was called. Emitted via
     * the {@link ApiError} string ctor because lib-errors is frozen (same pattern as
     * {@code LimitCheckUnavailableException}).
     */
    @ExceptionHandler(CorridorPricingUnavailableException.class)
    public ResponseEntity<ApiError> handleCorridorPricingUnavailable(
            CorridorPricingUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(ex.code(), ex.getMessage(), ex.retryable(), newRequestId()));
    }

    /**
     * T3-6: the resolved scheme is outside its seeded operating window ({@code scheme_operating_hours},
     * V024). 409 {@code SCHEME_CLOSED} with {@code retryable=false} — a real, structured state of the
     * corridor rather than a fault, and an immediate retry cannot succeed; the message names the window
     * and the scheme-LOCAL time so the caller knows when it reopens. Unlike the other stable-string
     * codes in this handler this one IS a canonical {@link ErrorCode} member, added to lib-errors with
     * this gap so payment-executor and smart-router emit one identical code.
     *
     * <p>Nothing was mutated: no float reserved or deducted, no transaction row, no scheme call, no
     * ledger posting, no event. Declared BEFORE the generic {@code PaymentException} paths.
     * Confirm/cancel/refund never reach this gate — an in-flight payment must still complete and a
     * refund must still be possible after the window closes.
     */
    @ExceptionHandler(SchemeClosedException.class)
    public ResponseEntity<ApiError> handleSchemeClosed(SchemeClosedException ex) {
        return ResponseEntity.status(ErrorCode.SCHEME_CLOSED.httpStatus())
                .body(ApiError.of(ErrorCode.SCHEME_CLOSED, ex.getMessage(), newRequestId()));
    }

    /**
     * T5-3: a sanctions/PEP screening refusal. 422 with {@code retryable=false} and the stable code
     * carried on the exception — {@code SANCTIONS_SCREENING_UNAVAILABLE} (nothing screened the parties
     * and {@code gmepay.screening.fail-closed} is armed) or {@code SANCTIONS_SCREENING_HIT} (an
     * authoritative provider returned an adverse verdict).
     *
     * <p><b>422, not 503.</b> Neither cause is a transient fault: a missing vendor needs a procurement
     * decision and a sanctions match needs a human disposition, so a caller must stop retrying — the
     * same reasoning as {@code SCHEME_OPERATION_UNSUPPORTED}. A 503 with {@code retryable=true} would
     * turn a compliance refusal into a retry storm against the same refusal.
     *
     * <p>Nothing was mutated: the gate runs at the start of a new authorization, so no float was
     * reserved or deducted, no transaction row was created, no scheme was called, no ledger posting and
     * no event. Confirm/cancel/refund never reach the gate. Emitted via the {@link ApiError} string ctor
     * because lib-errors was outside this change's ownership (same pattern as
     * {@code LimitCheckUnavailableException}); promoting both codes to {@link ErrorCode} members is a
     * listed follow-up.
     */
    @ExceptionHandler(PaymentScreeningRefusedException.class)
    public ResponseEntity<ApiError> handlePaymentScreeningRefused(PaymentScreeningRefusedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError(ex.code(), ex.getMessage(), ex.retryable(), newRequestId()));
    }

    @ExceptionHandler(SchemeDeclinedException.class)
    public ResponseEntity<ApiError> handleSchemeDeclined(SchemeDeclinedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ErrorCode.SCHEME_UNAVAILABLE,
                        ex.getMessage(), newRequestId()));
    }

    /**
     * T2-7: the resolved scheme has no cancel/refund round-trip (NEPAL / SENDMN are single-shot).
     * 422 with the STABLE {@code SCHEME_OPERATION_UNSUPPORTED} code and {@code retryable=false} — it is
     * a contract fact, not a decline and not a transient fault, so the caller must stop retrying and
     * route the reversal through the manual/ops process. Declared BEFORE the generic
     * {@code PaymentException} paths; emitted via the {@link ApiError} string ctor because lib-errors is
     * frozen (not yet an {@link ErrorCode} member — same pattern as {@code OperationalGateException}).
     */
    @ExceptionHandler(SchemeOperationNotSupportedException.class)
    public ResponseEntity<ApiError> handleSchemeOperationNotSupported(
            SchemeOperationNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError(ex.code(), ex.getMessage(), false, newRequestId()));
    }

    /**
     * T2-6: the requested refund amount cannot be accepted. Carries the exception's own stable code so a
     * caller can distinguish the three cases without string-matching a message:
     * {@code REFUND_AMOUNT_EXCEEDS_ORIGINAL} (422, not retryable — the cumulative guard fired),
     * {@code REFUND_AMOUNT_INVALID} (422, not retryable — non-positive or foreign currency) and
     * {@code REFUND_BASIS_UNAVAILABLE} (503, retryable — the original payment could not be read, so we
     * refuse rather than refund an unvalidated amount). Declared BEFORE the generic
     * {@code PaymentException} paths; emitted via the {@link ApiError} string ctor because lib-errors is
     * frozen (same pattern as {@code SchemeOperationNotSupportedException}).
     */
    @ExceptionHandler(RefundAmountInvalidException.class)
    public ResponseEntity<ApiError> handleRefundAmountInvalid(RefundAmountInvalidException ex) {
        HttpStatus status = ex.retryable()
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .body(new ApiError(ex.code(), ex.getMessage(), ex.retryable(), newRequestId()));
    }

    /**
     * T2-6: a PARTIAL refund was asked of a scheme whose adapter contract carries no refund amount. 422 with
     * the stable {@code PARTIAL_REFUND_UNSUPPORTED} code and {@code retryable=false} — refusing is correct,
     * because the only instruction we could send is a FULL cancel, which would over-refund the customer at
     * the scheme while our books recorded the smaller figure. Nothing was mutated: no float moved, no status
     * was written, no journal was posted.
     */
    @ExceptionHandler(PartialRefundNotSupportedException.class)
    public ResponseEntity<ApiError> handlePartialRefundNotSupported(
            PartialRefundNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError(ex.code(), ex.getMessage(), false, newRequestId()));
    }

    @ExceptionHandler(SchemeTimeoutException.class)
    public ResponseEntity<ApiError> handleSchemeTimeout(SchemeTimeoutException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(ErrorCode.SCHEME_UNAVAILABLE,
                        ex.getMessage(), newRequestId()));
    }

    /**
     * GET /v1/payments/{id} miss or cross-partner access (5.2-T16). 404 {@code PAYMENT_NOT_FOUND} —
     * now the canonical {@link ErrorCode#PAYMENT_NOT_FOUND} (Phase 2: the String-literal workaround is
     * retired). A cross-partner payment maps here too (never 403) so ownership is not leaked.
     */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiError> handlePaymentNotFound(PaymentNotFoundException ex) {
        return ResponseEntity.status(ErrorCode.PAYMENT_NOT_FOUND.httpStatus())
                .body(ApiError.of(ErrorCode.PAYMENT_NOT_FOUND, ex.getMessage(), newRequestId()));
    }

    /**
     * Strict-mode merchant resolution failure (lookup miss / unreachable, dev-synth disabled) →
     * canonical {@link ErrorCode#MERCHANT_NOT_FOUND} (404). Declared BEFORE the {@code IllegalArgument}
     * handler; {@code MerchantNotFoundException} extends {@code PaymentException} (RuntimeException) so it
     * needs its own mapping rather than falling through to a generic 500.
     */
    @ExceptionHandler(MerchantNotFoundException.class)
    public ResponseEntity<ApiError> handleMerchantNotFound(MerchantNotFoundException ex) {
        return ResponseEntity.status(ErrorCode.MERCHANT_NOT_FOUND.httpStatus())
                .body(ApiError.of(ErrorCode.MERCHANT_NOT_FOUND, ex.getMessage(), newRequestId()));
    }

    /**
     * Operations operational gate (Ops wave): a NEW authorization was refused because the platform is
     * paused / in maintenance, or the resolved partner / scheme / route is suspended. Surfaced as a
     * 503 (retryable) with the STABLE canonical code carried on the exception
     * ({@code SYSTEM_PAUSED} / {@code PARTNER_SUSPENDED} / {@code SCHEME_SUSPENDED} /
     * {@code ROUTE_SUSPENDED}). Emitted via the {@link ApiError} string ctor because lib-errors is
     * frozen — these codes are NOT yet {@link ErrorCode} enum members (integration request logged in
     * the CHANGELOG). In-flight confirm/refund/status paths do not reach this gate.
     */
    @ExceptionHandler(OperationalGateException.class)
    public ResponseEntity<ApiError> handleOperationalGate(OperationalGateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(ex.code(), ex.getMessage(), true, newRequestId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleValidation(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(ErrorCode.VALIDATION_ERROR,
                        ex.getMessage(), newRequestId()));
    }

    /**
     * Prefer the per-request correlation id from the MDC (so the error id == the trace id and the
     * partner's error joins the server logs); fall back to a generated UUID so the field is never null.
     */
    private static String newRequestId() {
        String correlationId = MDC.get(CorrelationHeaders.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId;
        }
        return UUID.randomUUID().toString();
    }
}
