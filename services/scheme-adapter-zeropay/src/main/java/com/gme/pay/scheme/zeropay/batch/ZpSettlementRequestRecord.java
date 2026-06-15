package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Domain record representing one detail line in a ZP0061 (morning) or ZP0063 (afternoon)
 * settlement request file sent to ZeroPay (SCH-06 §7.1).
 *
 * <p>All KRW amounts are BigDecimal with zero decimal places (integers).</p>
 *
 * <p>Fixed-width layout (total 120 chars per record, excluding line separator):
 * <pre>
 *  Offset  Width  Field
 *  0       1      recordType           CHAR(1)  "D"
 *  1       10     merchantId           CHAR(10)
 *  11      8      businessDate         DATE(8)  YYYYMMDD
 *  19      6      paymentCount         NUM(6)   total approved payments in window
 *  25      15     grossAmountKrw       NUM(15)  sum of all approved payment amounts
 *  40      6      refundCount          NUM(6)   total refunds in window
 *  46      15     refundAmountKrw      NUM(15)  sum of all refund amounts
 *  61      15     netAmountKrw         NUM(15)  grossAmountKrw - refundAmountKrw
 *  76      15     merchantFeeKrw       NUM(15)  aggregate merchant fee
 *  91      15     vanFeeKrw            NUM(15)  aggregate VAN fee
 *  106     14     reserved             CHAR(14) space-filled (reserved for future use)
 * </pre>
 * Total detail record length: 120 characters.
 * </p>
 * <!-- TODO: confirm field widths from BS-07 §7.1 when formal spec is received -->
 */
public record ZpSettlementRequestRecord(
        String merchantId,
        LocalDate businessDate,
        int paymentCount,
        BigDecimal grossAmountKrw,
        int refundCount,
        BigDecimal refundAmountKrw,
        BigDecimal netAmountKrw,
        BigDecimal merchantFeeKrw,
        BigDecimal vanFeeKrw
) {

    static final int RECORD_TYPE_OFFSET     = 0;
    static final int MERCHANT_ID_OFFSET     = 1;
    static final int MERCHANT_ID_LEN        = 10;
    static final int BUSINESS_DATE_OFFSET   = 11;
    static final int BUSINESS_DATE_LEN      = 8;
    static final int PAYMENT_COUNT_OFFSET   = 19;
    static final int PAYMENT_COUNT_LEN      = 6;
    static final int GROSS_AMT_OFFSET       = 25;
    static final int GROSS_AMT_LEN          = 15;
    static final int REFUND_COUNT_OFFSET    = 40;
    static final int REFUND_COUNT_LEN       = 6;
    static final int REFUND_AMT_OFFSET      = 46;
    static final int REFUND_AMT_LEN         = 15;
    static final int NET_AMT_OFFSET         = 61;
    static final int NET_AMT_LEN            = 15;
    static final int MERCHANT_FEE_OFFSET    = 76;
    static final int MERCHANT_FEE_LEN       = 15;
    static final int VAN_FEE_OFFSET         = 91;
    static final int VAN_FEE_LEN            = 15;
    static final int RESERVED_OFFSET        = 106;
    static final int RESERVED_LEN          = 14;

    /** Total length of one detail record line. */
    static final int RECORD_LENGTH          = 120;
}
