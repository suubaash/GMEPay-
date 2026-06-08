package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Domain record representing one detail line in a ZP0011 payment-result file (SCH-06 §5.2).
 *
 * <p>All KRW amounts are BigDecimal with zero decimal places (integers), per canonical facts.</p>
 *
 * <p>Fixed-width layout (total 117 chars per record, excluding line separator):</p>
 * <pre>
 *  Offset  Width  Field
 *  0       1      recordType           CHAR(1)  "D" for detail
 *  1       20     gmeTxnId             CHAR(20)
 *  21      20     zeroPayTxnRef        CHAR(20)
 *  41      10     merchantId           CHAR(10)
 *  51      20     qrCodeId             CHAR(20)
 *  71      8      txnDate              DATE(8)  YYYYMMDD
 *  79      6      txnTime              TIME(6)  HHmmss
 *  85      12     payoutAmountKrw      NUM(12)  zero-padded integer
 *  97      12     merchantFeeAmt       NUM(12)  zero-padded integer
 *  109     10     vanFeeAmt            NUM(10)  zero-padded integer (note: NUM(10) per spec)
 *  119     1      partnerType          CHAR(1)  "D"=domestic "I"=international
 *  120     12     approvalCode         CHAR(12)
 *  132     1      statusCode           CHAR(1)  "A"=approved
 * </pre>
 * Total detail record length: 133 characters.
 */
public record Zp0011Record(
        String gmeTxnId,
        String zeroPayTxnRef,
        String merchantId,
        String qrCodeId,
        LocalDate txnDate,
        LocalTime txnTime,
        BigDecimal payoutAmountKrw,
        BigDecimal merchantFeeAmt,
        BigDecimal vanFeeAmt,
        char partnerType,
        String approvalCode,
        char statusCode
) {

    static final int RECORD_TYPE_OFFSET   = 0;
    static final int GME_TXN_ID_OFFSET    = 1;
    static final int GME_TXN_ID_LEN       = 20;
    static final int ZP_TXN_REF_OFFSET    = 21;
    static final int ZP_TXN_REF_LEN       = 20;
    static final int MERCHANT_ID_OFFSET   = 41;
    static final int MERCHANT_ID_LEN      = 10;
    static final int QR_CODE_ID_OFFSET    = 51;
    static final int QR_CODE_ID_LEN       = 20;
    static final int TXN_DATE_OFFSET      = 71;
    static final int TXN_DATE_LEN         = 8;
    static final int TXN_TIME_OFFSET      = 79;
    static final int TXN_TIME_LEN         = 6;
    static final int PAYOUT_AMT_OFFSET    = 85;
    static final int PAYOUT_AMT_LEN       = 12;
    static final int MERCHANT_FEE_OFFSET  = 97;
    static final int MERCHANT_FEE_LEN     = 12;
    static final int VAN_FEE_OFFSET       = 109;
    static final int VAN_FEE_LEN          = 10;
    static final int PARTNER_TYPE_OFFSET  = 119;
    static final int APPROVAL_CODE_OFFSET = 120;
    static final int APPROVAL_CODE_LEN    = 12;
    static final int STATUS_CODE_OFFSET   = 132;

    /** Total length of one detail record line in bytes/chars. */
    static final int RECORD_LENGTH        = 133;
}
