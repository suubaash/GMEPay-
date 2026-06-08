package com.gme.pay.payment.domain.client;

import java.math.BigDecimal;

/**
 * Interface to the Prefunding service.
 * Implementations call prefunding's REST API; tests use hand-written fakes.
 */
public interface PrefundingClient {

    /**
     * Atomically deducts {@code amountUsd} from the partner's prefunding balance.
     *
     * @param partnerId    the partner whose balance to deduct from
     * @param txnRef       transaction reference for the ledger entry
     * @param amountUsd    the USD amount to deduct
     * @return the result containing balance after deduction
     * @throws com.gme.pay.payment.domain.InsufficientPrefundingException if balance is too low
     */
    DeductionResult deduct(long partnerId, String txnRef, BigDecimal amountUsd);

    /**
     * Reverses a prior deduction (CREDIT_REVERSAL ledger entry).
     *
     * @param partnerId the partner whose balance to credit back
     * @param txnRef    the original transaction reference to reverse
     */
    void reverse(long partnerId, String txnRef);

    /** Result returned by a successful deduction. */
    record DeductionResult(BigDecimal deductedUsd, BigDecimal balanceAfter) {}
}
