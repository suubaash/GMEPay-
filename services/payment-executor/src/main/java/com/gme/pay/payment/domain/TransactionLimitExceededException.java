package com.gme.pay.payment.domain;

import java.math.BigDecimal;

/**
 * Thrown during AUTHORIZE when a transaction breaches a configured per-partner limit (per-transaction
 * min or max, in major USD units — V020 partner_limits), before any side effect. Covers the statutory
 * 소액해외송금업 (small-amount overseas remittance) per-transaction ceiling. Declining here — before the
 * merchant is resolved, the PENDING txn is created, or the float is reserved — keeps an over-limit
 * remittance from leaving any trace. Mapped to HTTP 422 {@code TRANSACTION_LIMIT_EXCEEDED}.
 */
public class TransactionLimitExceededException extends PaymentException {

    public TransactionLimitExceededException(String partnerId, BigDecimal amountUsd,
                                             BigDecimal limitUsd, String bound) {
        super("partner '" + partnerId + "' transaction " + amountUsd + " USD breaches the per-transaction "
                + bound + " limit of " + limitUsd + " USD");
    }
}
