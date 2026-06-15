package com.gme.pay.ledger.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.ledger.revenue.RevenueRecord;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response body for {@code POST /v1/revenue/capture}: the stored revenue record. Money fields
 * serialize as decimal strings per {@code docs/MONEY_CONVENTION.md}.
 */
public record RevenueCaptureResponse(
        String txnRef,
        long partnerId,
        long schemeId,
        LocalDate revenueDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fxMarginUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal serviceChargeAmount,
        String serviceChargeCcy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeSharePct
) {
    static RevenueCaptureResponse from(RevenueRecord r) {
        return new RevenueCaptureResponse(
                r.txnRef(), r.partnerId(), r.schemeId(), r.revenueDate(),
                r.fxMarginUsd(), r.serviceChargeAmount(), r.serviceChargeCcy(), r.feeSharePct());
    }
}
