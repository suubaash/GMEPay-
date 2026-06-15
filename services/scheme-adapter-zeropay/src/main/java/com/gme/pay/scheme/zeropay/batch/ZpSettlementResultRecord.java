package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Domain record representing one detail line in a ZP0062 (morning settlement result, ~10:00 KST)
 * or ZP0064 (afternoon settlement result, ~19:00 KST) file received from ZeroPay (SCH-06 §7.2).
 *
 * <p>ZeroPay confirms or adjusts the settlement totals per merchant in the response file.</p>
 *
 * <p>Fixed-width layout (total 120 chars per record, mirrors ZpSettlementRequestRecord):
 * <pre>
 *  Offset  Width  Field
 *  0       1      recordType           CHAR(1)  "D"
 *  1       10     merchantId           CHAR(10)
 *  11      8      businessDate         DATE(8)  YYYYMMDD
 *  19      6      paymentCount         NUM(6)   confirmed payment count
 *  25      15     confirmedGrossKrw    NUM(15)  confirmed gross amount
 *  40      6      refundCount          NUM(6)   confirmed refund count
 *  46      15     confirmedRefundKrw   NUM(15)  confirmed refund amount
 *  61      15     confirmedNetKrw      NUM(15)  confirmedGrossKrw - confirmedRefundKrw
 *  76      15     merchantFeeKrw       NUM(15)  ZeroPay-computed merchant fee
 *  91      15     vanFeeKrw            NUM(15)  ZeroPay-computed VAN fee
 *  106     4      resultCode           CHAR(4)  "0000"=OK
 *  110     10     reserved             CHAR(10) space-filled
 * </pre>
 * Total detail record length: 120 characters.
 * </p>
 * <!-- TODO: confirm resultCode placement and field widths from BS-07 §7.2 when received -->
 */
public record ZpSettlementResultRecord(
        String merchantId,
        LocalDate businessDate,
        int paymentCount,
        BigDecimal confirmedGrossKrw,
        int refundCount,
        BigDecimal confirmedRefundKrw,
        BigDecimal confirmedNetKrw,
        BigDecimal merchantFeeKrw,
        BigDecimal vanFeeKrw,
        String resultCode
) {

    static final int RECORD_TYPE_OFFSET       = 0;
    static final int MERCHANT_ID_OFFSET       = 1;
    static final int MERCHANT_ID_LEN          = 10;
    static final int BUSINESS_DATE_OFFSET     = 11;
    static final int BUSINESS_DATE_LEN        = 8;
    static final int PAYMENT_COUNT_OFFSET     = 19;
    static final int PAYMENT_COUNT_LEN        = 6;
    static final int CONFIRMED_GROSS_OFFSET   = 25;
    static final int CONFIRMED_GROSS_LEN      = 15;
    static final int REFUND_COUNT_OFFSET      = 40;
    static final int REFUND_COUNT_LEN         = 6;
    static final int CONFIRMED_REFUND_OFFSET  = 46;
    static final int CONFIRMED_REFUND_LEN     = 15;
    static final int CONFIRMED_NET_OFFSET     = 61;
    static final int CONFIRMED_NET_LEN        = 15;
    static final int MERCHANT_FEE_OFFSET      = 76;
    static final int MERCHANT_FEE_LEN         = 15;
    static final int VAN_FEE_OFFSET           = 91;
    static final int VAN_FEE_LEN              = 15;
    static final int RESULT_CODE_OFFSET       = 106;
    static final int RESULT_CODE_LEN          = 4;
    static final int RESERVED_OFFSET          = 110;
    static final int RESERVED_LEN             = 10;

    /** Total length of one detail record line. */
    static final int RECORD_LENGTH            = 120;

    static final String RESULT_SUCCESS        = "0000";
}
