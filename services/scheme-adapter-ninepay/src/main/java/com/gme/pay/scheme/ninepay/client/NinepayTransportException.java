package com.gme.pay.scheme.ninepay.client;

/**
 * An AMBIGUOUS failure talking to 9Pay: timeout, connection reset, 5xx, malformed or
 * unverifiable response. The transfer may or may not have been received/executed —
 * per the 9Pay integration spec (timeout handling, section 5) the caller MUST resolve
 * the true state by polling {@code /service/transfer/info} with the original
 * {@code request_id} before any retry. Never resubmit on this exception.
 */
public class NinepayTransportException extends RuntimeException {

    public NinepayTransportException(String message) {
        super(message);
    }

    public NinepayTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
