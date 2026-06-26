package com.gme.pay.prefunding.api;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link ApiException} onto the canonical {@link ApiError} envelope with the HTTP status its
 * {@code ErrorCode} carries — mirroring {@code RegistryApiExceptionHandler}. Without this, prefunding's
 * {@code ApiException} throws fell through to Spring's default 500 handler, so a short-float reserve
 * surfaced as 500 instead of 402 INSUFFICIENT_PREFUNDING (the status {@code RestPrefundingClient} keys
 * off), and an AML cumulative breach would not surface as 422 CUMULATIVE_LIMIT_EXCEEDED.
 *
 * <p>ApiException-only: {@code ResponseStatusException} (BalanceProvisioningController's channel) keeps
 * Spring's stock handling.
 */
@RestControllerAdvice
public class PrefundingApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(ApiError.of(ex.errorCode(), ex.getMessage(), null));
    }
}
