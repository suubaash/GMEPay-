package com.gme.pay.payment.web;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.payment.domain.InsufficientPrefundingException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
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
