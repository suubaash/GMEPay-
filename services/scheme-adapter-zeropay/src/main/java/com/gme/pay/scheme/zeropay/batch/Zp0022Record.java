package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Domain record representing one detail line in a ZP0022 refund-result-response file
 * (SCH-06 §6.3 — inbound, ZeroPay -> GME, ~05:00 KST).
 *
 * <p>ZP0022 mirrors ZP0012 but is the response to ZP0021 (refund registrations).
 * The {@code resultCode} field carries the per-transaction disposition for each refund.</p>
 *
 * <p>Fixed-width layout (total 110 chars per record, same dimensions as ZP0012):
 * <pre>
 *  Offset  Width  Field
 *  0       1      recordType           CHAR(1)  "D"
 *  1       20     gmeTxnId             CHAR(20) echoed from ZP0021
 *  21      20     zeroPayTxnRef        CHAR(20)
 *  41      10     merchantId           CHAR(10)
 *  51      8      businessDate         DATE(8)  YYYYMMDD
 *  59      12     refundAmountKrw      NUM(12)
 *  71      4      resultCode           CHAR(4)  "0000"=success
 *  75      20     resultMessage        CHAR(20)
 *  95      15     reserved             CHAR(15) space-filled
 * </pre>
 * Total detail record length: 110 characters.
 * </p>
 * <!-- TODO: confirm from BS-04 §6.3 when formal spec received -->
 */
public record Zp0022Record(
        String gmeTxnId,
        String zeroPayTxnRef,
        String merchantId,
        LocalDate businessDate,
        BigDecimal refundAmountKrw,
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
    static final int REFUND_AMT_OFFSET     = 59;
    static final int REFUND_AMT_LEN        = 12;
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
