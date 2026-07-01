package com.gme.pay.payment.web;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.payment.domain.CumulativeLimitExceededException;
import com.gme.pay.payment.domain.InsufficientPrefundingException;
import com.gme.pay.payment.domain.MerchantNotFoundException;
import com.gme.pay.payment.domain.OperationalGateException;
import com.gme.pay.payment.domain.PaymentNotFoundException;
import com.gme.pay.payment.domain.QuoteAmountMismatchException;
import com.gme.pay.payment.domain.SchemeBalanceUnavailableException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.payment.domain.TransactionLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(SchemeDeclinedException.class)
    public ResponseEntity<ApiError> handleSchemeDeclined(SchemeDeclinedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ErrorCode.SCHEME_UNAVAILABLE,
                        ex.getMessage(), newRequestId()));
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

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
