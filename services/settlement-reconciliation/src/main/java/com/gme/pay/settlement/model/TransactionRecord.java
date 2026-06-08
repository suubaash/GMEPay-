package com.gme.pay.settlement.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Projection of a transaction row as consumed by the Settlement Engine.
 * Data originates from transaction-mgmt via its API — never from a shared DB table.
 *
 * <p>Key settlement fields:
 * <ul>
 *   <li>{@code targetPayoutKrw} — amount ZeroPay credits to the merchant's KRW account</li>
 *   <li>{@code settlementType} — 'N' for domestic NET, 'G' for international GROSS</li>
 *   <li>{@code merchantFeeRate} — decimal fee rate (e.g. 0.008 for 0.8%), from scheme fee table</li>
 * </ul>
 */
public record TransactionRecord(
        Long id,
        String txnRef,
        String schemeRef,
        String merchantId,
        BigDecimal targetPayoutKrw,
        char settlementType,
        BigDecimal merchantFeeRate,  // 0 for GROSS; rate from scheme fee table for NET
        String status,
        OffsetDateTime completedAt,
        Long settlementBatchId       // null = unbatched
) {

    public boolean isNet() {
        return settlementType == 'N';
    }

    public boolean isGross() {
        return settlementType == 'G';
    }

    public boolean isApproved() {
        return "APPROVED".equalsIgnoreCase(status);
    }
}
