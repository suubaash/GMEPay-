package com.gme.pay.ratefx.web;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain errors onto the canonical API error envelope (API-05 error model).
 * {@link ApiException} carries its {@link ErrorCode} directly; the rate engine's
 * {@code IllegalArgument}/{@code IllegalState} messages are prefixed with a code
 * name ("MIN_MARGIN_VIOLATION: ...") which is resolved here.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return toResponse(ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return toResponse(codeFromMessage(ex.getMessage(), ErrorCode.VALIDATION_ERROR), ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex) {
        return toResponse(codeFromMessage(ex.getMessage(), ErrorCode.INTERNAL_ERROR), ex.getMessage());
    }

    private static ErrorCode codeFromMessage(String message, ErrorCode fallback) {
        if (message != null) {
            int colon = message.indexOf(':');
            if (colon > 0) {
                try {
                    return ErrorCode.valueOf(message.substring(0, colon).trim());
                } catch (IllegalArgumentException notACode) {
                    // fall through to the fallback code
                }
            }
        }
        return fallback;
    }

    private static ResponseEntity<ApiError> toResponse(ErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus()).body(ApiError.of(code, message, null));
    }
}
