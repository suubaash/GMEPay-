package com.gme.pay.settlement.builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Input to a {@link AbstractZeroPayFileBuilder} — the OUTPUT of the booking math, decoupled from the
 * fixed-width file layout. One {@link MerchantRow} per merchant (= settlement counterparty) per window;
 * {@code netSettlementAmount} is the Addendum-001 booked amount under that partner's rounding mode.
 *
 * @param yyyymmdd  settlement business date as YYYYMMDD (KST)
 * @param sequence  intra-day file sequence (1-based)
 * @param rows      per-merchant settlement rows
 */
public record BuildContext(String yyyymmdd, int sequence, List<MerchantRow> rows) {

    /**
     * @param netSettlementAmount the BOOKED net (Addendum-001), emitted in the file
     * @param roundingResidual    precise − booked (carried for the REVENUE_ROUNDING post)
     * @param mode                the rounding mode actually used (locked for audit)
     * @param settlementType      'N' (NET/domestic) or 'G' (GROSS/international)
     */
    public record MerchantRow(
            String merchantId,
            int grossTxnCount,
            BigDecimal grossTxnAmount,
            int refundCount,
            BigDecimal refundAmount,
            BigDecimal merchantFeeTotal,
            BigDecimal netSettlementAmount,
            BigDecimal roundingResidual,
            RoundingMode mode,
            char settlementType) {}
}
