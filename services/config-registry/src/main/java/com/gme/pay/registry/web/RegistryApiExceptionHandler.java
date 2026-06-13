package com.gme.pay.registry.web;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link ApiException} onto the canonical {@link ApiError} envelope
 * (API-05 error model) with the status its {@code ErrorCode} carries.
 *
 * <p>Added in Slice 8 so the post-activation immutability rejections
 * ({@code IMMUTABLE_AFTER_ACTIVATION}, HTTP 400) surface as structured errors
 * the Admin UI can key off — previously config-registry's few
 * {@code ApiException} throws fell through to Spring's default 500 handler.
 * Scope is deliberately ApiException-only: {@code ResponseStatusException}
 * (the service layer's main error channel) keeps Spring's stock handling, and
 * {@code IllegalArgument}/{@code IllegalState} stay 500s (they indicate bugs
 * here, unlike rate-fx where they encode domain verdicts).
 */
@RestControllerAdvice
public class RegistryApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(ApiError.of(ex.errorCode(), ex.getMessage(), null));
    }
}
