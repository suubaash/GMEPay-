package com.gme.sim.wallet.service;

/**
 * Thrown when a downstream simulator (scheme or rate) is unreachable.
 * Mapped to HTTP 503 by GlobalExceptionHandler.
 */
public class SimDownException extends RuntimeException {
    public SimDownException(String message) {
        super(message);
    }
}
