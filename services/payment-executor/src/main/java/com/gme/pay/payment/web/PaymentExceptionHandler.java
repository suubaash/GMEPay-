package com.gme.pay.payment.web;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.payment.domain.CumulativeLimitExceededException;
import com.gme.pay.payment.domain.InsufficientPrefundingException;
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
     * GET /v1/payments/{id} miss or cross-partner access (5.2-T16). 404 with a {@code PAYMENT_NOT_FOUND}
     * string code — lib-errors' {@code ErrorCode} enum has no such constant (frozen), so we use the
     * {@link ApiError} canonical constructor directly rather than {@code ApiError.of(ErrorCode,...)}.
     */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiError> handlePaymentNotFound(PaymentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("PAYMENT_NOT_FOUND", ex.getMessage(), false, newRequestId()));
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
