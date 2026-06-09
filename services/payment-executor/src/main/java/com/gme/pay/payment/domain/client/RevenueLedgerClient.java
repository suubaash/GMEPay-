package com.gme.pay.payment.domain.client;

import java.math.BigDecimal;

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
}
