package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.scheme.zeropay.adapter.model.BatchType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.gme.pay.scheme.zeropay.batch.ZpFileFormatUtils.DATE_FMT;
import static com.gme.pay.scheme.zeropay.batch.ZpFileFormatUtils.padLeftZero;
import static com.gme.pay.scheme.zeropay.batch.ZpFileFormatUtils.padRight;

/**
 * Formats ZP0061 (morning settlement request, ~05:00 KST) and ZP0063 (afternoon, ~14:00 KST)
 * batch files as fixed-width text (SCH-06 §7.1).
 *
 * <p>The only difference between ZP0061 and ZP0063 is the 6-char file type code in the header.
 * Both use {@link ZpSettlementRequestRecord} for details.</p>
 *
 * <p>Header fixed-width layout (50 chars):
 * <pre>
 *  Offset  Width  Field
 *  0       6      fileType             "ZP0061" or "ZP0063"
 *  6       8      businessDate         YYYYMMDD
 *  14      10     gmeInstitutionCode   CHAR(10)
 *  24      6      totalRecordCount     NUM(6)   merchant-level detail lines
 *  30      15     totalNetAmountKrw    NUM(15)  sum of all netAmountKrw
 *  45      5      reserved             CHAR(5)  space-filled
 * </pre>
 * Total header length: 50 characters.
 * </p>
 *
 * <p>Trailer fixed-width layout (28 chars):
 * <pre>
 *  Offset  Width  Field
 *  0       1      "T"
 *  1       6      totalRecordCount     NUM(6)
 *  7       15     controlSum           NUM(15) sum of netAmountKrw
 *  22      6      reserved             CHAR(6)
 * </pre>
 * Total trailer length: 28 characters.
 * </p>
 *
 * <!-- TODO: confirm header/trailer reserved bytes + exact total lengths from BS-07 §7.1 -->
 */
public final class ZpSettlementRequestFormatter {

    static final int FILE_TYPE_LEN            = 6;
    static final int BUSINESS_DATE_LEN        = 8;
    static final int INSTITUTION_CODE_LEN     = 10;
    static final int HEADER_RECORD_COUNT_LEN  = 6;
    static final int HEADER_NET_AMOUNT_LEN    = 15;
    static final int HEADER_RESERVED_LEN      = 5;
    static final int HEADER_RECORD_LENGTH     = 50;

    static final int TRAILER_RECORD_COUNT_LEN = 6;
    static final int TRAILER_CONTROL_SUM_LEN  = 15;
    static final int TRAILER_RESERVED_LEN     = 6;
    static final int TRAILER_RECORD_LENGTH    = 28;

    private final String gmeInstitutionCode;

    public ZpSettlementRequestFormatter(String gmeInstitutionCode) {
        this.gmeInstitutionCode = gmeInstitutionCode;
    }

    /**
     * Formats a complete ZP0061 or ZP0063 file.
     *
     * @param fileType     must be {@link BatchType#ZP0061} or {@link BatchType#ZP0063}
     * @param businessDate KST business date
     * @param records      per-merchant settlement lines; may be empty
     * @return UTF-8 bytes of the complete fixed-width file
     */
    public byte[] format(BatchType fileType, LocalDate businessDate,
                         List<ZpSettlementRequestRecord> records) {
        if (fileType != BatchType.ZP0061 && fileType != BatchType.ZP0063) {
            throw new IllegalArgumentException(
                    "ZpSettlementRequestFormatter only supports ZP0061/ZP0063; got: " + fileType);
        }
        String typeCode = fileType.name(); // "ZP0061" or "ZP0063"

        BigDecimal totalNet = records.stream()
                .map(ZpSettlementRequestRecord::netAmountKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder();
        sb.append(formatHeader(typeCode, businessDate, records.size(), totalNet));
        sb.append('\n');

        for (ZpSettlementRequestRecord r : records) {
            sb.append(formatDetail(r));
            sb.append('\n');
        }

        sb.append(formatTrailer(records.size(), totalNet));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Package-private for testing
    // -----------------------------------------------------------------------

    String formatHeader(String typeCode, LocalDate businessDate, int recordCount,
                        BigDecimal totalNet) {
        StringBuilder h = new StringBuilder(HEADER_RECORD_LENGTH);
        h.append(padRight(typeCode,                  FILE_TYPE_LEN));
        h.append(businessDate.format(DATE_FMT));
        h.append(padRight(gmeInstitutionCode,        INSTITUTION_CODE_LEN));
        h.append(padLeftZero(String.valueOf(recordCount), HEADER_RECORD_COUNT_LEN));
        h.append(padLeftZero(totalNet.toBigIntegerExact().toString(), HEADER_NET_AMOUNT_LEN));
        h.append(padRight("",                        HEADER_RESERVED_LEN));
        return h.toString();
    }

    String formatDetail(ZpSettlementRequestRecord r) {
        StringBuilder d = new StringBuilder(ZpSettlementRequestRecord.RECORD_LENGTH);
        d.append('D');
        d.append(padRight(r.merchantId(),              ZpSettlementRequestRecord.MERCHANT_ID_LEN));
        d.append(r.businessDate().format(DATE_FMT));
        d.append(padLeftZero(String.valueOf(r.paymentCount()),
                ZpSettlementRequestRecord.PAYMENT_COUNT_LEN));
        d.append(padLeftZero(r.grossAmountKrw().toBigIntegerExact().toString(),
                ZpSettlementRequestRecord.GROSS_AMT_LEN));
        d.append(padLeftZero(String.valueOf(r.refundCount()),
                ZpSettlementRequestRecord.REFUND_COUNT_LEN));
        d.append(padLeftZero(r.refundAmountKrw().toBigIntegerExact().toString(),
                ZpSettlementRequestRecord.REFUND_AMT_LEN));
        d.append(padLeftZero(r.netAmountKrw().toBigIntegerExact().toString(),
                ZpSettlementRequestRecord.NET_AMT_LEN));
        d.append(padLeftZero(r.merchantFeeKrw().toBigIntegerExact().toString(),
                ZpSettlementRequestRecord.MERCHANT_FEE_LEN));
        d.append(padLeftZero(r.vanFeeKrw().toBigIntegerExact().toString(),
                ZpSettlementRequestRecord.VAN_FEE_LEN));
        d.append(padRight("", ZpSettlementRequestRecord.RESERVED_LEN));
        return d.toString();
    }

    String formatTrailer(int recordCount, BigDecimal controlSum) {
        StringBuilder t = new StringBuilder(TRAILER_RECORD_LENGTH);
        t.append('T');
        t.append(padLeftZero(String.valueOf(recordCount), TRAILER_RECORD_COUNT_LEN));
        t.append(padLeftZero(controlSum.toBigIntegerExact().toString(), TRAILER_CONTROL_SUM_LEN));
        t.append(padRight("", TRAILER_RESERVED_LEN));
        return t.toString();
    }
}
