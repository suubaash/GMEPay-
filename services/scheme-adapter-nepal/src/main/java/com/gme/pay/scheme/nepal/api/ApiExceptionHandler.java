package com.gme.pay.scheme.nepal.api;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link ApiException} into the canonical {@link ApiError} envelope with the
 * error code's HTTP status, so payment-executor sees the same error contract from Nepal as
 * from other services.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handle(ApiException ex) {
        ApiError body = ApiError.of(ex.errorCode(), ex.getMessage(), null);
        return ResponseEntity.status(ex.errorCode().httpStatus()).body(body);
    }
}
