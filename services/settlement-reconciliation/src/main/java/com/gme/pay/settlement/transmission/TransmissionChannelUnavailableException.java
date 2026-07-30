package com.gme.pay.settlement.transmission;

/**
 * Thrown when something tries to record a settlement batch as transmitted (or as having failed a
 * transmission) while no real transmission channel is configured.
 *
 * <p>GAP T4-5: this is the fail-closed half of the honesty fix. The reconciliation path used to
 * walk a batch through {@code TRANSMITTED} as bookkeeping; now the only writer of that state
 * refuses outright, so "a batch that was never sent reads as sent" cannot be reintroduced by a
 * later change without the change failing loudly first.
 */
public class TransmissionChannelUnavailableException extends RuntimeException {

    public TransmissionChannelUnavailableException(String message) {
        super(message);
    }
}
