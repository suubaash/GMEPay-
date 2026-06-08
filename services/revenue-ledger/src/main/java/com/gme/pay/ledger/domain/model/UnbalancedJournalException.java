package com.gme.pay.ledger.domain.model;

import java.math.BigDecimal;

/**
 * Thrown when a journal's debit total does not equal its credit total for a given currency.
 * This is always a programming error — the caller must compose balanced entries.
 */
public class UnbalancedJournalException extends RuntimeException {

    private final String currency;
    private final BigDecimal debits;
    private final BigDecimal credits;

    public UnbalancedJournalException(String currency, BigDecimal debits, BigDecimal credits) {
        super(String.format("Unbalanced journal for %s: debits=%s credits=%s delta=%s",
                currency, debits, credits, debits.subtract(credits)));
        this.currency = currency;
        this.debits = debits;
        this.credits = credits;
    }

    public String currency() { return currency; }
    public BigDecimal debits() { return debits; }
    public BigDecimal credits() { return credits; }
}
