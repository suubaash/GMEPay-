package com.gme.pay.ledger.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A single line in a double-entry journal. Each entry carries an amount (always positive),
 * a currency, an account code, and a side (DEBIT or CREDIT).
 * A valid journal is balanced: sum of DEBITs == sum of CREDITs (same currency).
 */
public final class LedgerEntry {

    private final String account;
    private final BigDecimal amount;
    private final String currency;
    private final EntryType type;
    private final String reference;

    public LedgerEntry(String account, BigDecimal amount, String currency, EntryType type, String reference) {
        Objects.requireNonNull(account, "account required");
        Objects.requireNonNull(amount, "amount required");
        Objects.requireNonNull(currency, "currency required");
        Objects.requireNonNull(type, "type required");
        Objects.requireNonNull(reference, "reference required");
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("LedgerEntry amount must be non-negative, got: " + amount);
        }
        this.account = account;
        this.amount = amount;
        this.currency = currency;
        this.type = type;
        this.reference = reference;
    }

    public String account() { return account; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public EntryType type() { return type; }
    public String reference() { return reference; }

    @Override
    public String toString() {
        return type + " " + account + " " + amount + " " + currency + " [" + reference + "]";
    }
}
