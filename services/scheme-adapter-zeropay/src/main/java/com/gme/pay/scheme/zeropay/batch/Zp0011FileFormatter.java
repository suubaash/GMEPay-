package com.gme.pay.scheme.zeropay.batch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Formats a ZP0011 payment-result file as a fixed-width text file (SCH-06 §5.2).
 *
 * <p>All CHAR fields are left-aligned, space-padded on the right and truncated if too long.
 * All NUM fields are right-aligned, zero-padded on the left. KRW amounts are integers.</p>
 *
 * <p>Line separator is {@code \n} (LF). The final line has no trailing separator.</p>
 */
public final class Zp0011FileFormatter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");

    private final String gmeInstitutionCode;

    public Zp0011FileFormatter(String gmeInstitutionCode) {
        this.gmeInstitutionCode = gmeInstitutionCode;
    }

    /**
     * Formats a complete ZP0011 file from the given header-level metadata and detail records.
     *
     * @param businessDate     KST business date for the file
     * @param records          approved-transaction records to include; may be empty
     * @return UTF-8 bytes of the complete fixed-width file
     */
    public byte[] format(LocalDate businessDate, List<Zp0011Record> records) {
        StringBuilder sb = new StringBuilder();

        // compute totals for header and trailer
        BigDecimal totalPayout = records.stream()
                .map(Zp0011Record::payoutAmountKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // --- Header ---
        sb.append(formatHeader(businessDate, records.size(), totalPayout));
        sb.append('\n');

        // --- Detail records ---
        for (Zp0011Record r : records) {
            sb.append(formatDetail(r));
            sb.append('\n');
        }

        // --- Trailer ---
        sb.append(formatTrailer(records.size(), totalPayout));

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Package-private for testing
    // -----------------------------------------------------------------------

    String formatHeader(LocalDate businessDate, int recordCount, BigDecimal totalPayout) {
        StringBuilder h = new StringBuilder(Zp0011HeaderRecord.RECORD_LENGTH);
        h.append(padRight(Zp0011HeaderRecord.FILE_TYPE_VALUE, Zp0011HeaderRecord.FILE_TYPE_LEN));
        h.append(businessDate.format(DATE_FMT));
        h.append(padRight(gmeInstitutionCode, Zp0011HeaderRecord.INSTITUTION_CODE_LEN));
        h.append(padLeftZero(String.valueOf(recordCount), Zp0011HeaderRecord.RECORD_COUNT_LEN));
        h.append(padLeftZero(totalPayout.toBigIntegerExact().toString(),
                Zp0011HeaderRecord.TOTAL_AMOUNT_LEN));
        return h.toString();
    }

    String formatDetail(Zp0011Record r) {
        StringBuilder d = new StringBuilder(Zp0011Record.RECORD_LENGTH);
        d.append('D');
        d.append(padRight(r.gmeTxnId(),     Zp0011Record.GME_TXN_ID_LEN));
        d.append(padRight(r.zeroPayTxnRef(),Zp0011Record.ZP_TXN_REF_LEN));
        d.append(padRight(r.merchantId(),   Zp0011Record.MERCHANT_ID_LEN));
        d.append(padRight(r.qrCodeId(),     Zp0011Record.QR_CODE_ID_LEN));
        d.append(r.txnDate().format(DATE_FMT));
        d.append(r.txnTime().format(TIME_FMT));
        d.append(padLeftZero(r.payoutAmountKrw().toBigIntegerExact().toString(),
                Zp0011Record.PAYOUT_AMT_LEN));
        d.append(padLeftZero(r.merchantFeeAmt().toBigIntegerExact().toString(),
                Zp0011Record.MERCHANT_FEE_LEN));
        d.append(padLeftZero(r.vanFeeAmt().toBigIntegerExact().toString(),
                Zp0011Record.VAN_FEE_LEN));
        d.append(r.partnerType());
        d.append(padRight(r.approvalCode(), Zp0011Record.APPROVAL_CODE_LEN));
        d.append(r.statusCode());
        return d.toString();
    }

    String formatTrailer(int recordCount, BigDecimal controlSum) {
        StringBuilder t = new StringBuilder(Zp0011TrailerRecord.RECORD_LENGTH);
        t.append('T');
        t.append(padLeftZero(String.valueOf(recordCount), Zp0011TrailerRecord.RECORD_COUNT_LEN));
        t.append(padLeftZero(controlSum.toBigIntegerExact().toString(),
                Zp0011TrailerRecord.CONTROL_SUM_LEN));
        return t.toString();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Left-aligns {@code value} in a field of {@code width}, space-padding on the right.
     * Truncates if the value exceeds the field width.
     */
    static String padRight(String value, int width) {
        if (value == null) value = "";
        if (value.length() >= width) return value.substring(0, width);
        return String.format("%-" + width + "s", value);
    }

    /**
     * Right-aligns {@code value} in a field of {@code width}, zero-padding on the left.
     * Throws if the value exceeds the field width.
     */
    static String padLeftZero(String value, int width) {
        if (value == null) value = "0";
        if (value.length() > width) {
            throw new IllegalArgumentException(
                    "Numeric value '" + value + "' exceeds field width " + width);
        }
        return String.format("%0" + width + "d", Long.parseLong(value));
    }
}
