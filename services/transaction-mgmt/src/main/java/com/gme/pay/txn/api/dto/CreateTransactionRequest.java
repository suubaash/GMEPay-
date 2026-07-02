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
 *
 * <h2>Wave-3 rate-lock pool fields (additive, IR-txn-2 / FX1015)</h2>
 *
 * <p>Appended so the rate-locked pool can be persisted at creation for
 * margin-accurate FX1015 and event re-emit. All nullable; existing callers that
 * omit them keep current behaviour. Money/rate values ride as decimal strings.
 * <ul>
 *   <li>{@code offerRateColl} – locked offer rate on the collection leg (nullable)</li>
 *   <li>{@code crossRate}     – target_payout / send_amount (nullable)</li>
 *   <li>{@code costRateColl} / {@code costRatePay} – per-leg cost rates (nullable)</li>
 *   <li>{@code collectionUsd}        – collection-leg USD amount (nullable)</li>
 *   <li>{@code payoutUsdCost}        – payout-leg USD cost (nullable)</li>
 *   <li>{@code collectionMarginUsd} / {@code payoutMarginUsd} – per-leg USD margins (nullable)</li>
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
        BigDecimal merchantFeeRate,
        BigDecimal offerRateColl,
        BigDecimal crossRate,
        BigDecimal costRateColl,
        BigDecimal costRatePay,
        BigDecimal collectionUsd,
        BigDecimal payoutUsdCost,
        BigDecimal collectionMarginUsd,
        BigDecimal payoutMarginUsd,
        /**
         * CS quick-wins (V011): the end-customer / wallet identifier carried on the wallet payment.
         * Optional — persisted onto the txn so customer support can look a payment up by what the
         * CUSTOMER holds. Null / omitted leaves it empty (current behaviour).
         */
        String userRef
) {
    /** Backwards-compatible 12-arg constructor (pre-Wave-3 shape); pool + userRef fields default null. */
    public CreateTransactionRequest(
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
            BigDecimal merchantFeeRate) {
        this(partnerId, partnerTxnRef, schemeId, direction, paymentMode, targetPayout,
                payoutCurrency, collectionAmount, collectionCurrency, merchantId, quoteId,
                merchantFeeRate, null, null, null, null, null, null, null, null, null);
    }

    /** Back-compat 20-arg constructor (Wave-3 shape, pre CS quick-wins); userRef defaults null. */
    public CreateTransactionRequest(
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
            BigDecimal merchantFeeRate,
            BigDecimal offerRateColl,
            BigDecimal crossRate,
            BigDecimal costRateColl,
            BigDecimal costRatePay,
            BigDecimal collectionUsd,
            BigDecimal payoutUsdCost,
            BigDecimal collectionMarginUsd,
            BigDecimal payoutMarginUsd) {
        this(partnerId, partnerTxnRef, schemeId, direction, paymentMode, targetPayout,
                payoutCurrency, collectionAmount, collectionCurrency, merchantId, quoteId,
                merchantFeeRate, offerRateColl, crossRate, costRateColl, costRatePay,
                collectionUsd, payoutUsdCost, collectionMarginUsd, payoutMarginUsd, null);
    }
}
