package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses ZP0062 (morning settlement result, ~10:00 KST) and ZP0064 (afternoon, ~19:00 KST)
 * files received from ZeroPay (SCH-06 §7.2).
 *
 * <p>Both files share the same fixed-width structure; the parser is parameterised by the
 * expected file-type code ("ZP0062" or "ZP0064").</p>
 *
 * <p>Validates:</p>
 * <ul>
 *   <li>Header file-type code matches the expected type code.</li>
 *   <li>Trailer record count matches actual detail lines.</li>
 *   <li>Trailer control sum matches sum of {@code confirmedNetKrw}.</li>
 * </ul>
 *
 * <p>Header layout (50 chars, mirrors ZP0061/0063 outbound header):
 * <pre>
 *  0-5    fileType             "ZP0062" or "ZP0064"
 *  6-13   businessDate         YYYYMMDD
 *  14-23  institutionCode      CHAR(10)
 *  24-29  totalRecordCount     NUM(6)
 *  30-44  totalNetAmountKrw    NUM(15)
 *  45-49  reserved             CHAR(5)
 * </pre>
 * </p>
 *
 * <p>Trailer layout (28 chars):
 * <pre>
 *  0      "T"
 *  1-6    totalRecordCount NUM(6)
 *  7-21   controlSum       NUM(15)
 *  22-27  reserved         CHAR(6)
 * </pre>
 * </p>
 */
public final class ZpSettlementResultParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    static final int FILE_TYPE_LEN            = 6;
    static final int HEADER_RECORD_LENGTH     = 50;
    static final int TRAILER_RECORD_COUNT_LEN = 6;
    static final int TRAILER_CONTROL_SUM_LEN  = 15;
    static final int TRAILER_RECORD_LENGTH    = 28;

    private final String expectedFileType;  // "ZP0062" or "ZP0064"

    /**
     * @param expectedFileType must be "ZP0062" or "ZP0064"
     */
    public ZpSettlementResultParser(String expectedFileType) {
        if (!"ZP0062".equals(expectedFileType) && !"ZP0064".equals(expectedFileType)) {
            throw new IllegalArgumentException(
                    "ZpSettlementResultParser only supports ZP0062/ZP0064; got: " + expectedFileType);
        }
        this.expectedFileType = expectedFileType;
    }

    /**
     * Parses all detail records from a ZP0062 or ZP0064 file.
     *
     * @param fileBytes UTF-8 bytes of the complete file
     * @return ordered list of merchant-level settlement result records
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} if validation fails
     */
    public List<ZpSettlementResultRecord> parse(byte[] fileBytes) {
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        if (lines.length < 2) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    expectedFileType + " file must have at least a header and trailer line");
        }

        parseAndValidateHeader(lines[0]);
        TrailerCounts trailer = parseTrailer(lines[lines.length - 1]);

        List<ZpSettlementResultRecord> records = new ArrayList<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (line.charAt(0) != 'D') {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        expectedFileType + ": unexpected record type '" + line.charAt(0)
                                + "' at line " + (i + 1));
            }
            records.add(parseDetail(line));
        }

        if (records.size() != trailer.recordCount()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    expectedFileType + " trailer record count " + trailer.recordCount()
                            + " does not match actual detail count " + records.size());
        }

        BigDecimal actualSum = records.stream()
                .map(ZpSettlementResultRecord::confirmedNetKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (actualSum.compareTo(trailer.controlSum()) != 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    expectedFileType + " trailer control sum " + trailer.controlSum()
                            + " does not match computed sum " + actualSum);
        }

        return records;
    }

    // -----------------------------------------------------------------------
    // Package-private for testing
    // -----------------------------------------------------------------------

    void parseAndValidateHeader(String line) {
        if (line.length() < HEADER_RECORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    expectedFileType + " header line too short: " + line.length());
        }
        String fileType = line.substring(0, FILE_TYPE_LEN).trim();
        if (!expectedFileType.equals(fileType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Expected " + expectedFileType + " file type, got: " + fileType);
        }
    }

    TrailerCounts parseTrailer(String line) {
        if (line.length() < TRAILER_RECORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    expectedFileType + " trailer line too short: " + line.length());
        }
        if (line.charAt(0) != 'T') {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    expectedFileType + " trailer must start with 'T', got: " + line.charAt(0));
        }
        int recordCount = Integer.parseInt(
                line.substring(1, 1 + TRAILER_RECORD_COUNT_LEN).trim());
        BigDecimal controlSum = new BigDecimal(
                line.substring(1 + TRAILER_RECORD_COUNT_LEN,
                               1 + TRAILER_RECORD_COUNT_LEN + TRAILER_CONTROL_SUM_LEN).trim());
        return new TrailerCounts(recordCount, controlSum);
    }

    ZpSettlementResultRecord parseDetail(String line) {
        if (line.length() < ZpSettlementResultRecord.RECORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    expectedFileType + " detail line too short: " + line.length());
        }
        String merchantId = line.substring(
                ZpSettlementResultRecord.MERCHANT_ID_OFFSET,
                ZpSettlementResultRecord.MERCHANT_ID_OFFSET + ZpSettlementResultRecord.MERCHANT_ID_LEN).trim();
        LocalDate businessDate = LocalDate.parse(
                line.substring(
                        ZpSettlementResultRecord.BUSINESS_DATE_OFFSET,
                        ZpSettlementResultRecord.BUSINESS_DATE_OFFSET + ZpSettlementResultRecord.BUSINESS_DATE_LEN),
                DATE_FMT);
        int paymentCount = Integer.parseInt(line.substring(
                ZpSettlementResultRecord.PAYMENT_COUNT_OFFSET,
                ZpSettlementResultRecord.PAYMENT_COUNT_OFFSET + ZpSettlementResultRecord.PAYMENT_COUNT_LEN).trim());
        BigDecimal confirmedGross = new BigDecimal(line.substring(
                ZpSettlementResultRecord.CONFIRMED_GROSS_OFFSET,
                ZpSettlementResultRecord.CONFIRMED_GROSS_OFFSET + ZpSettlementResultRecord.CONFIRMED_GROSS_LEN).trim());
        int refundCount = Integer.parseInt(line.substring(
                ZpSettlementResultRecord.REFUND_COUNT_OFFSET,
                ZpSettlementResultRecord.REFUND_COUNT_OFFSET + ZpSettlementResultRecord.REFUND_COUNT_LEN).trim());
        BigDecimal confirmedRefund = new BigDecimal(line.substring(
                ZpSettlementResultRecord.CONFIRMED_REFUND_OFFSET,
                ZpSettlementResultRecord.CONFIRMED_REFUND_OFFSET + ZpSettlementResultRecord.CONFIRMED_REFUND_LEN).trim());
        BigDecimal confirmedNet = new BigDecimal(line.substring(
                ZpSettlementResultRecord.CONFIRMED_NET_OFFSET,
                ZpSettlementResultRecord.CONFIRMED_NET_OFFSET + ZpSettlementResultRecord.CONFIRMED_NET_LEN).trim());
        BigDecimal merchantFee = new BigDecimal(line.substring(
                ZpSettlementResultRecord.MERCHANT_FEE_OFFSET,
                ZpSettlementResultRecord.MERCHANT_FEE_OFFSET + ZpSettlementResultRecord.MERCHANT_FEE_LEN).trim());
        BigDecimal vanFee = new BigDecimal(line.substring(
                ZpSettlementResultRecord.VAN_FEE_OFFSET,
                ZpSettlementResultRecord.VAN_FEE_OFFSET + ZpSettlementResultRecord.VAN_FEE_LEN).trim());
        String resultCode = line.substring(
                ZpSettlementResultRecord.RESULT_CODE_OFFSET,
                ZpSettlementResultRecord.RESULT_CODE_OFFSET + ZpSettlementResultRecord.RESULT_CODE_LEN).trim();

        return new ZpSettlementResultRecord(merchantId, businessDate, paymentCount,
                confirmedGross, refundCount, confirmedRefund, confirmedNet, merchantFee, vanFee,
                resultCode);
    }

    record TrailerCounts(int recordCount, BigDecimal controlSum) {}
}
