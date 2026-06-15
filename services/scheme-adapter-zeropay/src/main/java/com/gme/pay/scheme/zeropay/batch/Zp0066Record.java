package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Domain record representing one detail line in a ZP0066 refund-detail file (SCH-06 §8.1,
 * evening settlement refund detail, ~22:00 KST alongside ZP0065).
 *
 * <p>ZP0066 mirrors ZP0065 but covers refunded transactions, used to reconcile net settlement.</p>
 *
 * <p>Fixed-width layout (total 150 chars per record, excluding line separator):
 * <pre>
 *  Offset  Width  Field
 *  0       1      recordType               CHAR(1)  "D"
 *  1       20     gmeTxnId                 CHAR(20)
 *  21      20     zeroPayTxnRef            CHAR(20)
 *  41      10     merchantId               CHAR(10)
 *  51      20     qrCodeId                 CHAR(20)
 *  71      8      originalTxnDate          DATE(8)  YYYYMMDD
 *  79      6      refundTime               TIME(6)  HHmmss
 *  85      12     refundAmountKrw          NUM(12)
 *  97      12     merchantFeeAmt           NUM(12)  fee reversal
 *  109     10     vanFeeAmt                NUM(10)  fee reversal
 *  119     1      partnerType              CHAR(1)  "D"/"I"
 *  120     12     originalApprovalCode     CHAR(12)
 *  132     1      statusCode               CHAR(1)  "R"=refunded
 *  133     8      settlementDate           DATE(8)  YYYYMMDD
 *  141     9      reserved                 CHAR(9)  space-filled
 * </pre>
 * Total detail record length: 150 characters.
 * </p>
 * <!-- TODO: confirm spec from BS-07 §8.1 when formally received -->
 */
public record Zp0066Record(
        String gmeTxnId,
        String zeroPayTxnRef,
        String merchantId,
        String qrCodeId,
        LocalDate originalTxnDate,
        LocalTime refundTime,
        BigDecimal refundAmountKrw,
        BigDecimal merchantFeeAmt,
        BigDecimal vanFeeAmt,
        char partnerType,
        String originalApprovalCode,
        char statusCode,
        LocalDate settlementDate
) {

    static final int RECORD_TYPE_OFFSET         = 0;
    static final int GME_TXN_ID_OFFSET          = 1;
    static final int GME_TXN_ID_LEN             = 20;
    static final int ZP_TXN_REF_OFFSET          = 21;
    static final int ZP_TXN_REF_LEN             = 20;
    static final int MERCHANT_ID_OFFSET         = 41;
    static final int MERCHANT_ID_LEN            = 10;
    static final int QR_CODE_ID_OFFSET          = 51;
    static final int QR_CODE_ID_LEN             = 20;
    static final int ORIGINAL_TXN_DATE_OFFSET   = 71;
    static final int ORIGINAL_TXN_DATE_LEN      = 8;
    static final int REFUND_TIME_OFFSET         = 79;
    static final int REFUND_TIME_LEN            = 6;
    static final int REFUND_AMT_OFFSET          = 85;
    static final int REFUND_AMT_LEN             = 12;
    static final int MERCHANT_FEE_OFFSET        = 97;
    static final int MERCHANT_FEE_LEN           = 12;
    static final int VAN_FEE_OFFSET             = 109;
    static final int VAN_FEE_LEN                = 10;
    static final int PARTNER_TYPE_OFFSET        = 119;
    static final int ORIG_APPROVAL_CODE_OFFSET  = 120;
    static final int ORIG_APPROVAL_CODE_LEN     = 12;
    static final int STATUS_CODE_OFFSET         = 132;
    static final int SETTLEMENT_DATE_OFFSET     = 133;
    static final int SETTLEMENT_DATE_LEN        = 8;
    static final int RESERVED_OFFSET            = 141;
    static final int RESERVED_LEN               = 9;

    /** Total length of one detail record line. */
    static final int RECORD_LENGTH              = 150;
}
