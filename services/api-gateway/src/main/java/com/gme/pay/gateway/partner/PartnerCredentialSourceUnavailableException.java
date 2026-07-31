package com.gme.pay.gateway.partner;

/**
 * Signals that the partner credential store could not be consulted (T0-7).
 *
 * <p>Distinct from "the key is unknown" on purpose. An unknown key is a decision — the request is
 * definitively not authentic, so the edge answers <b>401</b> and the partner should stop retrying.
 * An <em>unavailable store</em> is the absence of a decision: the gateway does not know whether the
 * key is valid, so the only safe answer is <b>503</b> (fail closed, retryable) — never "let it
 * through and hope".
 *
 * <p>Collapsing the two would be the classic fail-open bug: an outage of the credential store would
 * either admit every signed-looking request (if treated as success) or permanently blackhole real
 * partners with a non-retryable 401 (if treated as unknown).
 */
public class PartnerCredentialSourceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PartnerCredentialSourceUnavailableException(String message) {
        super(message);
    }

    public PartnerCredentialSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
