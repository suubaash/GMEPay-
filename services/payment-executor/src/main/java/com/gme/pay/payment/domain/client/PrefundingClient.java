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
     * Reverses a prior deduction (credits the originally-debited amount back).
     *
     * @param partnerId the partner whose balance to credit back
     * @param txnRef    the original transaction reference to reverse
     * @return the amount actually reversed + the resulting balance (so the cancel path can record the
     *         real reversed USD instead of a placeholder zero); {@code reversedUsd} is zero when there
     *         was nothing to reverse (idempotent)
     */
    ReverseResult reverse(long partnerId, String txnRef);

    /** Result returned by a successful deduction. */
    record DeductionResult(BigDecimal deductedUsd, BigDecimal balanceAfter) {}

    /** Result returned by a reversal. */
    record ReverseResult(BigDecimal reversedUsd, BigDecimal balanceAfter) {}
}
