package com.gme.pay.prefunding;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.math.BigDecimal;

/**
 * A partner's prepaid balance (OVERSEAS partners, USD). Encapsulates the deduction invariant:
 * the scheme is never called without a prior successful deduction, and the balance can never go
 * negative. In the service, {@code deduct} runs inside a DB transaction with SELECT ... FOR UPDATE
 * so concurrent payments are serialized; this class holds the business rule itself.
 */
public class PrefundingAccount {

    private final String partnerId;
    private final String currency;
    private BigDecimal balance;
    private BigDecimal reserved;
    private final BigDecimal creditLimit;
    private final BigDecimal lowBalanceThreshold;

    public PrefundingAccount(String partnerId, String currency, BigDecimal balance,
                             BigDecimal lowBalanceThreshold) {
        this(partnerId, currency, balance, BigDecimal.ZERO, BigDecimal.ZERO, lowBalanceThreshold);
    }

    /**
     * Full constructor (V005 reservation ledger). {@code reserved} is the sum of active holds and
     * {@code creditLimit} is the partner's credit headroom; funds available for a NEW reservation or
     * deduction are {@code balance + creditLimit - reserved}.
     */
    public PrefundingAccount(String partnerId, String currency, BigDecimal balance,
                             BigDecimal reserved, BigDecimal creditLimit,
                             BigDecimal lowBalanceThreshold) {
        this.partnerId = partnerId;
        this.currency = currency;
        this.balance = balance == null ? BigDecimal.ZERO : balance;
        this.reserved = reserved == null ? BigDecimal.ZERO : reserved;
        this.creditLimit = creditLimit == null ? BigDecimal.ZERO : creditLimit;
        this.lowBalanceThreshold = lowBalanceThreshold == null ? BigDecimal.ZERO : lowBalanceThreshold;
    }

    /** Funds available for a NEW reservation or deduction: balance + creditLimit - reserved. */
    public BigDecimal available() {
        return balance.add(creditLimit).subtract(reserved);
    }

    /**
     * Deduct {@code amount} directly; throws INSUFFICIENT_PREFUNDING (balance unchanged) if it would
     * exceed {@link #available()} (= balance + creditLimit - reserved). With no reservations and no
     * credit this is exactly the old "balance can't go negative" rule.
     */
    public synchronized BigDecimal deduct(BigDecimal amount) {
        requirePositive(amount);
        if (available().compareTo(amount) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_PREFUNDING,
                    "available " + available() + " " + currency + " < requested " + amount);
        }
        balance = balance.subtract(amount);
        return balance;
    }

    /** Record a top-up. */
    public synchronized BigDecimal credit(BigDecimal amount) {
        requirePositive(amount);
        balance = balance.add(amount);
        return balance;
    }

    /**
     * Place a hold for {@code amount} against available funds (authorize phase). Throws
     * INSUFFICIENT_PREFUNDING (nothing changes) if {@code amount} exceeds {@link #available()}.
     */
    public synchronized BigDecimal reserve(BigDecimal amount) {
        requirePositive(amount);
        if (available().compareTo(amount) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_PREFUNDING,
                    "available " + available() + " " + currency + " < requested " + amount);
        }
        reserved = reserved.add(amount);
        return reserved;
    }

    /**
     * Convert a hold into an actual debit (confirm phase): reduce BOTH the balance and the reserved
     * total by {@code amount}. The reservation must have been placed first.
     */
    public synchronized BigDecimal capture(BigDecimal amount) {
        requirePositive(amount);
        if (reserved.compareTo(amount) < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "capture " + amount + " exceeds reserved " + reserved);
        }
        reserved = reserved.subtract(amount);
        balance = balance.subtract(amount);
        return balance;
    }

    /** Release a hold without debiting (expiry / decline): reduce the reserved total by {@code amount}. */
    public synchronized BigDecimal release(BigDecimal amount) {
        requirePositive(amount);
        if (reserved.compareTo(amount) < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "release " + amount + " exceeds reserved " + reserved);
        }
        reserved = reserved.subtract(amount);
        return reserved;
    }

    public BigDecimal reserved() {
        return reserved;
    }

    public BigDecimal creditLimit() {
        return creditLimit;
    }

    public boolean isLowBalance() {
        return balance.compareTo(lowBalanceThreshold) < 0;
    }

    public BigDecimal balance() {
        return balance;
    }

    public String partnerId() {
        return partnerId;
    }

    public String currency() {
        return currency;
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "amount must be positive");
        }
    }
}
