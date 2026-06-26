package com.gme.pay.payment.domain;

/**
 * Thrown during AUTHORIZE when a transaction would push the partner's CUMULATIVE volume over a configured
 * rolling cap (daily / monthly / annual USD — V020 partner_limits). Surfaced from the prefunding service,
 * which makes the check race-free under its per-partner row lock. The orchestrator compensates (releases the
 * just-placed float hold + fails the PENDING txn) before rethrowing. Mapped to HTTP 422
 * {@code CUMULATIVE_LIMIT_EXCEEDED}.
 */
public class CumulativeLimitExceededException extends PaymentException {

    public CumulativeLimitExceededException(String detail) {
        super(detail);
    }
}
