package com.gme.pay.payment.domain.client;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Interface to the Revenue Ledger service (revenue-ledger).
 * Used by the payment commit path to post the per-partner settlement rounding residual
 * to {@code REVENUE_ROUNDING} per {@code docs/MONEY_CONVENTION.md}.
 *
 * <p>Owned by revenue-ledger per {@code docs/INTER_SERVICE_CONTRACTS.md} — payment-executor
 * consumes it only via this DTO surface, never by touching revenue-ledger's database.
 */
public interface RevenueLedgerClient {

    /**
     * Posts the rounding residual ({@code precise - booked}) to the rounding ledger.
     * No-op (no journal posted) when {@code residual} is zero.
     *
     * <p>Failures must NOT block the commit path — callers should log and continue (and
     * ideally enqueue an outbox event of type {@code revenue.residual.failed} for retry).
     *
     * @param reference the upstream transaction reference (audited on each ledger line)
     * @param residual  the rounding residual {@code precise - booked} in {@code currency}
     * @param currency  the ISO-4217 currency of {@code residual}
     */
    void postRoundingResidual(String reference, BigDecimal residual, String currency);

    /**
     * Captures one committed transaction's revenue (FX margin + service charge) in revenue-ledger so
     * its {@code GET /v1/revenue} reports real figures. Posts to {@code POST /v1/revenue/capture}
     * (idempotent by {@code txnRef}).
     *
     * <p>Like {@link #postRoundingResidual}, failures MUST NOT block the commit path — implementations
     * log and continue. The default is a no-op so existing single-method fakes/lambdas stay valid; the
     * production {@code RestRevenueLedgerClient} overrides it.
     *
     * @param txnRef              transaction-mgmt business reference (idempotency key)
     * @param partnerId           the partner
     * @param schemeId            numeric scheme id, or {@code 0} when the caller carries only the scheme code
     * @param revenueDate         the KST business date the revenue was earned
     * @param collectionMarginUsd USD margin on the collection leg ({@code 0} for same-currency)
     * @param payoutMarginUsd     USD margin on the payout leg ({@code 0} for same-currency)
     * @param serviceCharge       service-charge amount in {@code serviceChargeCcy}
     * @param serviceChargeCcy    ISO-4217 currency of the service charge
     * @param feeSharePct         scheme fee-share fraction (record metadata)
     */
    default void postRevenueCapture(String txnRef, long partnerId, long schemeId, LocalDate revenueDate,
                                    BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd,
                                    BigDecimal serviceCharge, String serviceChargeCcy,
                                    BigDecimal feeSharePct) {
        // no-op by default
    }

    /**
     * Posts a structured reversal journal to revenue-ledger when a payment is cancelled/refunded —
     * a balanced DEBIT REVENUE_REVERSAL / CREDIT RECEIVABLE_PARTNER for {@code reversalAmount}, so the
     * cancellation is booked rather than absorbed as a zero rounding residual. Posts to
     * {@code POST /v1/journals/reversal}.
     *
     * <p>Non-blocking like {@link #postRoundingResidual}; failures are logged, never thrown. Default
     * is a no-op so single-method fakes/lambdas stay valid; {@code RestRevenueLedgerClient} overrides.
     *
     * @param reference      the cancelled transaction reference
     * @param reversalAmount the amount being reversed (e.g. the prefund USD returned)
     * @param currency       ISO-4217 currency of {@code reversalAmount}
     */
    default void postReversalJournal(String reference, BigDecimal reversalAmount, String currency) {
        // no-op by default
    }
}
