package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.gme.pay.scheme.zeropay.batch.ZpFileFormatUtils.DATE_FMT;
import static com.gme.pay.scheme.zeropay.batch.ZpFileFormatUtils.TIME_FMT;
import static com.gme.pay.scheme.zeropay.batch.ZpFileFormatUtils.padLeftZero;
import static com.gme.pay.scheme.zeropay.batch.ZpFileFormatUtils.padRight;

/**
 * Formats a ZP0021 refund-result file as a fixed-width text file (SCH-06 §6.2).
 *
 * <p>Layout mirrors ZP0011: header (H) + N detail lines (D) + trailer (T).</p>
 *
 * <p>Header fixed-width layout (45 chars):
 * <pre>
 *  Offset  Width  Field
 *  0       6      fileType             "ZP0021"
 *  6       8      businessDate         YYYYMMDD
 *  14      10     gmeInstitutionCode   CHAR(10)
 *  24      6      totalRecordCount     NUM(6)
 *  30      15     totalRefundAmountKrw NUM(15)
 * </pre>
 * </p>
 *
 * <p>Trailer fixed-width layout (22 chars):
 * <pre>
 *  Offset  Width  Field
 *  0       1      "T"
 *  1       6      totalRecordCount     NUM(6)
 *  7       15     controlSum           NUM(15) sum of all refundAmountKrw
 * </pre>
 * </p>
 *
 * <!-- TODO: verify header/trailer lengths match ZP0021 spec once BS-04 §6.2 is finalised -->
 */
public final class Zp0021FileFormatter {

    static final String FILE_TYPE_VALUE          = "ZP0021";
    static final int    FILE_TYPE_LEN            = 6;
    static final int    BUSINESS_DATE_LEN        = 8;
    static final int    INSTITUTION_CODE_LEN     = 10;
    static final int    HEADER_RECORD_COUNT_LEN  = 6;
    static final int    HEADER_TOTAL_AMOUNT_LEN  = 15;
    static final int    HEADER_RECORD_LENGTH     = 45;

    static final int    TRAILER_RECORD_COUNT_LEN = 6;
    static final int    TRAILER_CONTROL_SUM_LEN  = 15;
    static final int    TRAILER_RECORD_LENGTH    = 22;

    private final String gmeInstitutionCode;

    public Zp0021FileFormatter(String gmeInstitutionCode) {
        this.gmeInstitutionCode = gmeInstitutionCode;
    }

    /**
     * Formats a complete ZP0021 file from refund records.
     *
     * @param businessDate KST business date for the file
     * @param records      refund records; may be empty
     * @return UTF-8 bytes of the complete fixed-width file
     */
    public byte[] format(LocalDate businessDate, List<Zp0021Record> records) {
        BigDecimal totalRefund = records.stream()
                .map(Zp0021Record::refundAmountKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder();
        sb.append(formatHeader(businessDate, records.size(), totalRefund));
        sb.append('\n');

        for (Zp0021Record r : records) {
            sb.append(formatDetail(r));
            sb.append('\n');
        }

        sb.append(formatTrailer(records.size(), totalRefund));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Package-private for testing
    // -----------------------------------------------------------------------

    String formatHeader(LocalDate businessDate, int recordCount, BigDecimal totalRefund) {
        StringBuilder h = new StringBuilder(HEADER_RECORD_LENGTH);
        h.append(padRight(FILE_TYPE_VALUE,           FILE_TYPE_LEN));
        h.append(businessDate.format(DATE_FMT));
        h.append(padRight(gmeInstitutionCode,        INSTITUTION_CODE_LEN));
        h.append(padLeftZero(String.valueOf(recordCount), HEADER_RECORD_COUNT_LEN));
        h.append(padLeftZero(totalRefund.toBigIntegerExact().toString(), HEADER_TOTAL_AMOUNT_LEN));
        return h.toString();
    }

    String formatDetail(Zp0021Record r) {
        StringBuilder d = new StringBuilder(Zp0021Record.RECORD_LENGTH);
        d.append('D');
        d.append(padRight(r.gmeTxnId(),             Zp0021Record.GME_TXN_ID_LEN));
        d.append(padRight(r.zeroPayTxnRef(),         Zp0021Record.ZP_TXN_REF_LEN));
        d.append(padRight(r.merchantId(),            Zp0021Record.MERCHANT_ID_LEN));
        d.append(padRight(r.qrCodeId(),              Zp0021Record.QR_CODE_ID_LEN));
        d.append(r.originalTxnDate().format(DATE_FMT));
        d.append(r.refundTime().format(TIME_FMT));
        d.append(padLeftZero(r.refundAmountKrw().toBigIntegerExact().toString(),
                Zp0021Record.REFUND_AMT_LEN));
        d.append(padLeftZero(r.merchantFeeAmt().toBigIntegerExact().toString(),
                Zp0021Record.MERCHANT_FEE_LEN));
        d.append(padLeftZero(r.vanFeeAmt().toBigIntegerExact().toString(),
                Zp0021Record.VAN_FEE_LEN));
        d.append(r.partnerType());
        d.append(padRight(r.originalApprovalCode(), Zp0021Record.ORIG_APPROVAL_CODE_LEN));
        d.append(r.statusCode());
        return d.toString();
    }

    String formatTrailer(int recordCount, BigDecimal controlSum) {
        StringBuilder t = new StringBuilder(TRAILER_RECORD_LENGTH);
        t.append('T');
        t.append(padLeftZero(String.valueOf(recordCount), TRAILER_RECORD_COUNT_LEN));
        t.append(padLeftZero(controlSum.toBigIntegerExact().toString(), TRAILER_CONTROL_SUM_LEN));
        return t.toString();
    }
}
