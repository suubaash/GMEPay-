package com.gme.pay.payment.domain;

import java.math.BigDecimal;

/**
 * Thrown when the settlement amount/currency the partner asserts in the payment request
 * ({@code collection_amount}/{@code collection_currency}) does not match the locked rate
 * quote it is paying against.
 *
 * <p>This enforces the partner's agreement to the settlement amount: the partner echoes the
 * amount it is charging the customer, and it MUST equal what we issued in the quote. Raised
 * before any side effect (transaction creation, prefunding deduction, scheme submission), so a
 * disagreement is rejected cleanly with nothing booked. Mapped to HTTP 422
 * {@code QUOTE_AMOUNT_MISMATCH}.
 */
public class QuoteAmountMismatchException extends PaymentException {

    public QuoteAmountMismatchException(BigDecimal requestedAmount, String requestedCurrency,
                                        BigDecimal quoteAmount, String quoteCurrency) {
        super(String.format(
                "collection amount/currency does not match the locked quote: "
                        + "requested %s %s, quote %s %s",
                requestedAmount, requestedCurrency, quoteAmount, quoteCurrency));
    }
}
