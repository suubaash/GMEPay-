package com.gme.sim.merchant.service;

/**
 * Thrown when sim-scheme (:9102) is unreachable or returns an unexpected error.
 * The MerchantController catches this and returns 503.
 */
public class SchemeUnavailableException extends RuntimeException {

    public SchemeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
