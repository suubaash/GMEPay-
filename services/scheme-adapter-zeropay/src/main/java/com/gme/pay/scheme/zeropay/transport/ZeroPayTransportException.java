package com.gme.pay.scheme.zeropay.transport;

/**
 * Raised when the ZeroPay 전문/TCP exchange fails at the transport level (connect refused, socket
 * timeout, truncated/malformed frame). Distinct from a decoded scheme DECLINE: a transport failure
 * means GME does not know the payment outcome and must NEVER treat it as an approval — the caller
 * maps it to a scheme-unavailable result so the two-phase confirm leaves the txn UNCERTAIN rather
 * than committing APPROVED.
 */
public class ZeroPayTransportException extends RuntimeException {

    public ZeroPayTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
