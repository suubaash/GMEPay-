package com.gme.sim.wallet.controller;

import com.gme.sim.wallet.service.SimDownException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps domain exceptions to friendly HTTP responses instead of 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SimDownException.class)
    public ResponseEntity<Map<String, String>> handleSimDown(SimDownException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "downstream_sim_unavailable",
                        "detail", ex.getMessage()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "detail", ex.getMessage()));
    }
}
