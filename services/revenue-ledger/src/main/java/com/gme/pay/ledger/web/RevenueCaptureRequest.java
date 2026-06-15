package com.gme.pay.ledger.web;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code POST /v1/revenue/capture} — one committed transaction's revenue, posted
 * (sync) by payment-executor after commit. Money fields ride as decimal strings per
 * {@code docs/MONEY_CONVENTION.md} (Jackson reads them into {@link BigDecimal}).
 *
 * <p>{@code fxMarginUsd = collectionMarginUsd + payoutMarginUsd}; both are 0 for same-currency
 * (domestic) transactions, where the service charge is the revenue.
 */
public record RevenueCaptureRequest(
        String txnRef,
        long partnerId,
        long schemeId,
        LocalDate revenueDate,
        BigDecimal collectionMarginUsd,
        BigDecimal payoutMarginUsd,
        BigDecimal serviceChargeAmount,
        String serviceChargeCcy,
        BigDecimal feeSharePct
) {}
