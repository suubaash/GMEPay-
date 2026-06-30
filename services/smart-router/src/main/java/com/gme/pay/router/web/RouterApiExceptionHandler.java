package com.gme.pay.router.web;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link ApiException} onto the canonical {@link ApiError} envelope
 * (API-05 error model) with the status its {@code ErrorCode} carries.
 *
 * <p>Phase 2: smart-router's resolution surface migrated off the former
 * router-local {@code ResolutionError} enum / {@code SchemeResolutionException}
 * onto the canonical {@code ErrorCode} + {@link ApiException}. This advice
 * renders every throw — the scheme-for-location branches
 * ({@code VALIDATION_ERROR} / {@code NO_SCHEME_FOR_LOCATION} /
 * {@code DIRECTION_NOT_ENABLED} / {@code PAYMENT_MODE_NOT_SUPPORTED}) as well as
 * the existing {@code SchemeRouter} country/partner throws — as a structured
 * {@code ApiError} body with the canonical HTTP status (e.g. 409 for
 * mode/direction). Previously the {@code SchemeRouter} {@code ApiException}
 * throws had no advice and fell through to Spring's default 500 handler.
 */
@RestControllerAdvice
public class RouterApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(ApiError.of(ex.errorCode(), ex.getMessage(), null));
    }
}
