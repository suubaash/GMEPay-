package com.gme.pay.payment.domain;

import java.math.BigDecimal;

/**
 * Thrown during AUTHORIZE when the scheme reports GME's prepaid balance with it (+ any per-scheme
 * credit) is insufficient to fund the merchant payout (SETTLEMENT_FLOW_SPEC §7.2). Declining here —
 * before the customer is charged — minimises the scheme-outage-after-charge case at confirm time.
 * Mapped to HTTP 402 {@code SCHEME_BALANCE_INSUFFICIENT}.
 */
public class SchemeBalanceUnavailableException extends PaymentException {

    public SchemeBalanceUnavailableException(String schemeId, BigDecimal amount, String currency) {
        super("scheme '" + schemeId + "' reports insufficient GME prepaid balance to fund "
                + amount + " " + currency);
    }
}
