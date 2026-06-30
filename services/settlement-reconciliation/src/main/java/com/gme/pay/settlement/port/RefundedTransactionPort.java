package com.gme.pay.settlement.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Cross-service port for transaction-mgmt's <strong>refund-date</strong> query
 * (Phase-2 contract, commit 5dbafd5):
 * {@code GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD}.
 *
 * <p>Unlike {@link TransactionQueryPort#findUnbatchedRefunded(LocalDate)} — which keys a refund to the
 * <em>creation</em> date of its original payment — this port keys the refund leg to the date the
 * <em>refund itself</em> was processed, and carries the <strong>original payment txnRef</strong> so the
 * settlement engine can net a cross-date claw-back back to the window that originally credited the
 * merchant (settlement IR-1). A refund of a prior-day payment surfaces here on its refund date.
 *
 * <p>MSA rule: settlement-reconciliation NEVER reads transaction-mgmt's database directly. The REST
 * implementation is gated; an in-process fixture is the fallback when the client is disabled or
 * transaction-mgmt is unreachable.
 */
public interface RefundedTransactionPort {

    /**
     * Return the refund legs processed on {@code refundedOn}, each keyed by its own refund txnRef and
     * carrying the original payment's txnRef for cross-date netting.
     *
     * @param refundedOn the calendar date (KST business date) on which the refund was processed
     * @return zero or more {@link RefundLeg}s; never null
     */
    List<RefundLeg> findRefundedOn(LocalDate refundedOn);

    /**
     * One refund leg as projected by transaction-mgmt's {@code /v1/transactions/refunded} endpoint.
     *
     * @param refundTxnRef    the txnRef of the refund transaction itself
     * @param originalTxnRef  the txnRef of the original payment being clawed back (netting key)
     * @param merchantId      the merchant whose net is reduced (ReconDiffEngine / netting key)
     * @param refundAmountKrw the KRW amount clawed back (positive magnitude)
     * @param refundedOn      the refund processing date echoed back
     * @param refundedAt      the precise refund timestamp, when available (else null)
     */
    record RefundLeg(
            String refundTxnRef,
            String originalTxnRef,
            String merchantId,
            BigDecimal refundAmountKrw,
            LocalDate refundedOn,
            OffsetDateTime refundedAt) {
    }
}
