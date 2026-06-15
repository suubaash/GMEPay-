package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Domain record representing one detail line in a ZP0065 payment-detail file (SCH-06 §8.1,
 * evening settlement detail, ~22:00 KST).
 *
 * <p>ZP0065 carries the full transaction-level detail needed to reconcile the final evening
 * settlement — it is more granular than the ZP0061/ZP0063 merchant-summary lines.</p>
 *
 * <p>Fixed-width layout (total 150 chars per record, excluding line separator):
 * <pre>
 *  Offset  Width  Field
 *  0       1      recordType           CHAR(1)  "D"
 *  1       20     gmeTxnId             CHAR(20)
 *  21      20     zeroPayTxnRef        CHAR(20)
 *  41      10     merchantId           CHAR(10)
 *  51      20     qrCodeId             CHAR(20)
 *  71      8      txnDate              DATE(8)  YYYYMMDD
 *  79      6      txnTime              TIME(6)  HHmmss
 *  85      12     payoutAmountKrw      NUM(12)
 *  97      12     merchantFeeAmt       NUM(12)
 *  109     10     vanFeeAmt            NUM(10)
 *  119     1      partnerType          CHAR(1)  "D"/"I"
 *  120     12     approvalCode         CHAR(12)
 *  132     1      statusCode           CHAR(1)  "A"=approved "C"=cancelled
 *  133     8      settlementDate       DATE(8)  YYYYMMDD settlement value date
 *  141     9      reserved             CHAR(9)  space-filled
 * </pre>
 * Total detail record length: 150 characters.
 * </p>
 * <!-- TODO: confirm extra settlement-date and reserved fields from BS-07 §8.1 spec -->
 */
public record Zp0065Record(
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
        char statusCode,
        LocalDate settlementDate
) {

    static final int RECORD_TYPE_OFFSET    = 0;
    static final int GME_TXN_ID_OFFSET     = 1;
    static final int GME_TXN_ID_LEN        = 20;
    static final int ZP_TXN_REF_OFFSET     = 21;
    static final int ZP_TXN_REF_LEN        = 20;
    static final int MERCHANT_ID_OFFSET    = 41;
    static final int MERCHANT_ID_LEN       = 10;
    static final int QR_CODE_ID_OFFSET     = 51;
    static final int QR_CODE_ID_LEN        = 20;
    static final int TXN_DATE_OFFSET       = 71;
    static final int TXN_DATE_LEN          = 8;
    static final int TXN_TIME_OFFSET       = 79;
    static final int TXN_TIME_LEN          = 6;
    static final int PAYOUT_AMT_OFFSET     = 85;
    static final int PAYOUT_AMT_LEN        = 12;
    static final int MERCHANT_FEE_OFFSET   = 97;
    static final int MERCHANT_FEE_LEN      = 12;
    static final int VAN_FEE_OFFSET        = 109;
    static final int VAN_FEE_LEN           = 10;
    static final int PARTNER_TYPE_OFFSET   = 119;
    static final int APPROVAL_CODE_OFFSET  = 120;
    static final int APPROVAL_CODE_LEN     = 12;
    static final int STATUS_CODE_OFFSET    = 132;
    static final int SETTLEMENT_DATE_OFFSET = 133;
    static final int SETTLEMENT_DATE_LEN   = 8;
    static final int RESERVED_OFFSET       = 141;
    static final int RESERVED_LEN          = 9;

    /** Total length of one detail record line. */
    static final int RECORD_LENGTH         = 150;
}
