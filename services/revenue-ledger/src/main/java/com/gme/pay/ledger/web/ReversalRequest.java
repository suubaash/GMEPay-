package com.gme.pay.ledger.web;

import java.math.BigDecimal;

/**
 * Wire payload for {@code POST /v1/journals/reversal} (P1-2 refund/cancel bookkeeping).
 *
 * <p>Posted by payment-executor's cancel path so a cancelled/refunded payment is contra-booked
 * (DEBIT REVENUE_REVERSAL / CREDIT RECEIVABLE_PARTNER) rather than absorbed as a zero residual.
 *
 * @param reference      the cancelled transaction reference (audited on each ledger line)
 * @param reversalAmount the amount being reversed (e.g. the prefund USD returned); zero = no-op
 * @param currency       the ISO-4217 currency of {@code reversalAmount}
 */
public record ReversalRequest(String reference, BigDecimal reversalAmount, String currency) {
}
