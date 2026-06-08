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
    private final BigDecimal lowBalanceThreshold;

    public PrefundingAccount(String partnerId, String currency, BigDecimal balance,
                             BigDecimal lowBalanceThreshold) {
        this.partnerId = partnerId;
        this.currency = currency;
        this.balance = balance == null ? BigDecimal.ZERO : balance;
        this.lowBalanceThreshold = lowBalanceThreshold == null ? BigDecimal.ZERO : lowBalanceThreshold;
    }

    /** Deduct {@code amount}; throws INSUFFICIENT_PREFUNDING (balance unchanged) if it would go negative. */
    public synchronized BigDecimal deduct(BigDecimal amount) {
        requirePositive(amount);
        if (balance.compareTo(amount) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_PREFUNDING,
                    "balance " + balance + " " + currency + " < requested " + amount);
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
