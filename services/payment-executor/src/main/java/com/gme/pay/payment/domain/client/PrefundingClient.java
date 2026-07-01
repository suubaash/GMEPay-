package com.gme.pay.payment.domain.client;

import com.gme.pay.contracts.PrefundingDeductionHistoryView;
import com.gme.pay.contracts.PrefundingReserveResponse;

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

    /**
     * Places a hold for {@code amountUsd} against the partner's available funds (authorize phase of
     * the two-phase flow). No balance is moved. Idempotent by {@code txnRef}.
     *
     * <p>Default throws {@link UnsupportedOperationException} so existing hand-written test fakes
     * remain valid; {@code RestPrefundingClient} provides the real implementation.
     *
     * @throws com.gme.pay.payment.domain.InsufficientPrefundingException if available funds &lt; amount
     */
    default ReservationResult reserve(long partnerId, String txnRef, BigDecimal amountUsd) {
        throw new UnsupportedOperationException("reserve not implemented in this PrefundingClient");
    }

    /**
     * Captures the active hold for {@code txnRef} (confirm phase) — converts the hold to a real
     * debit. Idempotent: no active hold ⇒ {@code capturedUsd = 0}.
     */
    default CaptureResult capture(long partnerId, String txnRef) {
        throw new UnsupportedOperationException("capture not implemented in this PrefundingClient");
    }

    /**
     * Releases the active hold for {@code txnRef} without debiting (expiry / decline). Idempotent:
     * no active hold ⇒ {@code releasedUsd = 0}.
     */
    default ReleaseResult release(long partnerId, String txnRef) {
        throw new UnsupportedOperationException("release not implemented in this PrefundingClient");
    }

    /**
     * AML cumulative cap (authorize phase): charge {@code amountUsd} toward the partner's daily/monthly/
     * annual usage, throwing {@link com.gme.pay.payment.domain.CumulativeLimitExceededException} if any
     * non-null cap would be breached. Race-free on the prefunding side (per-partner row lock). Only invoked
     * when a cap is actually configured; default throws so real impls must override.
     */
    default void chargeCumulative(long partnerId, String txnRef, BigDecimal amountUsd,
                                  BigDecimal dailyCapUsd, BigDecimal monthlyCapUsd, BigDecimal annualCapUsd,
                                  Integer dailyTxnCountLimit) {
        throw new UnsupportedOperationException("chargeCumulative not implemented in this PrefundingClient");
    }

    /**
     * Reverse a prior cumulative charge for {@code txnRef} (void / decline / expiry) so a held-but-not-
     * confirmed authorize does not permanently consume cap. Idempotent + best-effort: a no-op when nothing
     * was charged. Default is a NO-OP (not a throw) because it is called on every OVERSEAS release/void path,
     * including ones exercised by hand-written test fakes that predate cumulative caps.
     */
    default void reverseCumulative(long partnerId, String txnRef) {
        // no-op by default
    }

    /**
     * Read the partner's current prefunding balance (GET /v1/balance, backlog 5.2-T27). Read-only,
     * no hold/debit. Keyed by the partner's natural code (prefunding's {@code partner_balance} row key).
     *
     * <p>Default throws {@link UnsupportedOperationException} so existing hand-written test fakes remain
     * valid; {@code RestPrefundingClient} provides the real implementation against prefunding's
     * {@code GET /v1/prefunding/{partnerCode}/balance}.
     */
    default BalanceSnapshot balance(String partnerCode) {
        throw new UnsupportedOperationException("balance not implemented in this PrefundingClient");
    }

    /**
     * Read the partner's recent prefunding deduction history (GET /v1/prefunding/{code}/deductions?limit=N,
     * IR-pe-2) so the balance inquiry can answer {@code ?include_history=true}. Read-only; bounded by
     * {@code limit}, most-recent-first. Returns the canonical {@link PrefundingDeductionHistoryView}.
     *
     * <p>Default throws {@link UnsupportedOperationException} so existing hand-written test fakes remain
     * valid; {@code RestPrefundingClient} provides the real implementation.
     */
    default PrefundingDeductionHistoryView deductionHistory(String partnerCode, int limit) {
        throw new UnsupportedOperationException("deductionHistory not implemented in this PrefundingClient");
    }

    /**
     * RESERVE (soft-hold) a slice of the partner's float at OVERSEAS CPM token issuance, keyed/idempotent
     * on {@code idempotencyKey} (the canonical {@code PrefundingReserveRequest}/{@code Response} contract).
     * Distinct from the txnRef-keyed {@link #reserve(long, String, BigDecimal)} hold used by the MPM
     * authorize path: this is the CPM-token reserve qr-service/payment-executor binds for the
     * consumer-presented flow.
     *
     * @throws com.gme.pay.payment.domain.InsufficientPrefundingException if available funds &lt; amount
     */
    default PrefundingReserveResponse reserveCpm(long partnerId, BigDecimal amountUsd,
                                                 String idempotencyKey, String txnRef) {
        throw new UnsupportedOperationException("reserveCpm not implemented in this PrefundingClient");
    }

    /**
     * RELEASE a CPM reservation taken by {@link #reserveCpm} on token expiry / decline / abandonment.
     * Idempotent on {@code idempotencyKey} (reuse the reserve key); a release that already ran is a no-op.
     */
    default void releaseCpm(long partnerId, String reservationId, String idempotencyKey, String reason) {
        throw new UnsupportedOperationException("releaseCpm not implemented in this PrefundingClient");
    }

    /** Result returned by a successful deduction. */
    record DeductionResult(BigDecimal deductedUsd, BigDecimal balanceAfter) {}

    /**
     * A point-in-time prefunding balance read: the USD balance, the configured low-balance threshold,
     * and the balance currency. {@code threshold} may be null when no threshold is configured.
     */
    record BalanceSnapshot(BigDecimal balanceUsd, BigDecimal lowBalanceThresholdUsd, String currency) {}

    /** Result returned by a reversal. */
    record ReverseResult(BigDecimal reversedUsd, BigDecimal balanceAfter) {}

    /** Result of a reservation (hold): the amount held, available funds after, and the balance. */
    record ReservationResult(BigDecimal reservedUsd, BigDecimal available, BigDecimal balanceAfter) {}

    /** Result of a capture: the amount debited and the balance after. */
    record CaptureResult(BigDecimal capturedUsd, BigDecimal balanceAfter) {}

    /** Result of a release: the amount released and the balance (unchanged). */
    record ReleaseResult(BigDecimal releasedUsd, BigDecimal balanceAfter) {}
}
