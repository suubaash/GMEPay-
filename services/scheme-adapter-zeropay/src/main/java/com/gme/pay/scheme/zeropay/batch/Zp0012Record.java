package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Domain record representing one detail line in a ZP0012 payment-result-response file
 * (SCH-06 §5.3 — inbound, ZeroPay -> GME, ~05:00 KST).
 *
 * <p>ZeroPay returns ZP0012 to confirm or reject each record previously submitted in ZP0011.
 * The {@code resultCode} field carries the per-transaction disposition.</p>
 *
 * <p>Fixed-width layout (total 110 chars per record, excluding line separator):
 * <pre>
 *  Offset  Width  Field
 *  0       1      recordType           CHAR(1)  "D"
 *  1       20     gmeTxnId             CHAR(20) echoed from ZP0011
 *  21      20     zeroPayTxnRef        CHAR(20)
 *  41      10     merchantId           CHAR(10)
 *  51      8      businessDate         DATE(8)  YYYYMMDD
 *  59      12     payoutAmountKrw      NUM(12)
 *  71      4      resultCode           CHAR(4)  "0000"=success, other=error
 *  75      20     resultMessage        CHAR(20) human-readable reason
 *  95      15     reserved             CHAR(15) space-filled
 * </pre>
 * Total detail record length: 110 characters.
 * </p>
 * <!-- TODO: confirm field widths from BS-04 §5.3 when formal spec received -->
 */
public record Zp0012Record(
        String gmeTxnId,
        String zeroPayTxnRef,
        String merchantId,
        LocalDate businessDate,
        BigDecimal payoutAmountKrw,
        String resultCode,
        String resultMessage
) {

    static final int RECORD_TYPE_OFFSET    = 0;
    static final int GME_TXN_ID_OFFSET     = 1;
    static final int GME_TXN_ID_LEN        = 20;
    static final int ZP_TXN_REF_OFFSET     = 21;
    static final int ZP_TXN_REF_LEN        = 20;
    static final int MERCHANT_ID_OFFSET    = 41;
    static final int MERCHANT_ID_LEN       = 10;
    static final int BUSINESS_DATE_OFFSET  = 51;
    static final int BUSINESS_DATE_LEN     = 8;
    static final int PAYOUT_AMT_OFFSET     = 59;
    static final int PAYOUT_AMT_LEN        = 12;
    static final int RESULT_CODE_OFFSET    = 71;
    static final int RESULT_CODE_LEN       = 4;
    static final int RESULT_MSG_OFFSET     = 75;
    static final int RESULT_MSG_LEN        = 20;
    static final int RESERVED_OFFSET       = 95;
    static final int RESERVED_LEN          = 15;

    /** Total length of one detail record line. */
    static final int RECORD_LENGTH         = 110;

    /** Result code indicating successful processing. */
    static final String RESULT_SUCCESS     = "0000";
}
