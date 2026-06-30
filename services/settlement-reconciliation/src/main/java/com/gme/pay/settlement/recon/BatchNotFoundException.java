package com.gme.pay.settlement.recon;

/**
 * Raised when a recon run is requested for a settlement batch that does not exist — e.g. a ZP0064
 * afternoon result arrives before its ZP0063 batch was generated (backlog 7.1-T18 acceptance).
 */
public class BatchNotFoundException extends RuntimeException {

    public BatchNotFoundException(String message) {
        super(message);
    }
}
