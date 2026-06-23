package com.gme.pay.txn.api.dto;

import java.math.BigDecimal;

/**
 * Request body for POST /v1/transactions (create a new transaction).
 *
 * <p>Field names match payment-executor's {@code TransactionCreateRequest} EXACTLY
 * (Jackson binds by name — any mismatch silently produces null).
 *
 * <p>Canonical contract (payment-executor RestTransactionClient):
 * <ul>
 *   <li>{@code partnerId}          – long: the partner's numeric ID in config-registry</li>
 *   <li>{@code partnerTxnRef}      – String: partner's own reference for idempotency</li>
 *   <li>{@code schemeId}           – String: e.g. "zeropay_kr"</li>
 *   <li>{@code direction}          – String: INBOUND / OUTBOUND / DOMESTIC / HUB</li>
 *   <li>{@code paymentMode}        – String: QR / CARD / TRANSFER</li>
 *   <li>{@code targetPayout}       – BigDecimal: payout amount in payoutCurrency</li>
 *   <li>{@code payoutCurrency}     – String: ISO-4217 payout currency (e.g. "KRW")</li>
 *   <li>{@code collectionAmount}   – BigDecimal: amount collected from payer</li>
 *   <li>{@code collectionCurrency} – String: ISO-4217 collection currency (e.g. "USD")</li>
 *   <li>{@code merchantId}         – String: scheme merchant terminal identifier (nullable)</li>
 *   <li>{@code quoteId}            – String: rate-quote reference (nullable)</li>
 *   <li>{@code merchantFeeRate}    – BigDecimal: gross merchant fee rate resolved at
 *       creation (e.g. 0.0080 = 0.80%), snapshotted onto the txn (nullable — null leaves
 *       the snapshot empty and settlement treats it as 0)</li>
 * </ul>
 */
public record CreateTransactionRequest(
        long partnerId,
        String partnerTxnRef,
        String schemeId,
        String direction,
        String paymentMode,
        BigDecimal targetPayout,
        String payoutCurrency,
        BigDecimal collectionAmount,
        String collectionCurrency,
        String merchantId,
        String quoteId,
        BigDecimal merchantFeeRate
) {}
