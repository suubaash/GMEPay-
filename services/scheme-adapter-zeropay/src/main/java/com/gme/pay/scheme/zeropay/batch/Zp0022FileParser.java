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
 * Parses a ZP0022 refund-result-response file received from ZeroPay (SCH-06 §6.3).
 *
 * <p>Structurally identical to {@link Zp0012FileParser} but validates the header
 * file-type code is "ZP0022" and sums {@code refundAmountKrw} for the control sum.</p>
 */
public final class Zp0022FileParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    static final String FILE_TYPE_VALUE          = "ZP0022";
    static final int    FILE_TYPE_LEN            = 6;
    static final int    HEADER_RECORD_LENGTH     = 45;
    static final int    TRAILER_RECORD_COUNT_LEN = 6;
    static final int    TRAILER_CONTROL_SUM_LEN  = 15;
    static final int    TRAILER_RECORD_LENGTH    = 22;

    /**
     * Parses all detail records from a ZP0022 file.
     *
     * @param fileBytes UTF-8 bytes of the complete ZP0022 file
     * @return ordered list of detail records
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} if counts or control sum fail
     */
    public List<Zp0022Record> parse(byte[] fileBytes) {
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        if (lines.length < 2) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0022 file must have at least a header and trailer line");
        }

        parseAndValidateHeader(lines[0]);
        TrailerCounts trailer = parseTrailer(lines[lines.length - 1]);

        List<Zp0022Record> records = new ArrayList<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (line.charAt(0) != 'D') {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "ZP0022: unexpected record type '" + line.charAt(0) + "' at line " + (i + 1));
            }
            records.add(parseDetail(line));
        }

        if (records.size() != trailer.recordCount()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0022 trailer record count " + trailer.recordCount()
                            + " does not match actual detail count " + records.size());
        }

        BigDecimal actualSum = records.stream()
                .map(Zp0022Record::refundAmountKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (actualSum.compareTo(trailer.controlSum()) != 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0022 trailer control sum " + trailer.controlSum()
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
                    "ZP0022 header line too short: " + line.length());
        }
        String fileType = line.substring(0, FILE_TYPE_LEN).trim();
        if (!FILE_TYPE_VALUE.equals(fileType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Expected ZP0022 file type, got: " + fileType);
        }
    }

    TrailerCounts parseTrailer(String line) {
        if (line.length() < TRAILER_RECORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0022 trailer line too short: " + line.length());
        }
        if (line.charAt(0) != 'T') {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0022 trailer must start with 'T', got: " + line.charAt(0));
        }
        int recordCount = Integer.parseInt(
                line.substring(1, 1 + TRAILER_RECORD_COUNT_LEN).trim());
        BigDecimal controlSum = new BigDecimal(
                line.substring(1 + TRAILER_RECORD_COUNT_LEN,
                               1 + TRAILER_RECORD_COUNT_LEN + TRAILER_CONTROL_SUM_LEN).trim());
        return new TrailerCounts(recordCount, controlSum);
    }

    Zp0022Record parseDetail(String line) {
        if (line.length() < Zp0022Record.RECORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0022 detail line too short: " + line.length());
        }
        String gmeTxnId = line.substring(
                Zp0022Record.GME_TXN_ID_OFFSET,
                Zp0022Record.GME_TXN_ID_OFFSET + Zp0022Record.GME_TXN_ID_LEN).trim();
        String zpTxnRef = line.substring(
                Zp0022Record.ZP_TXN_REF_OFFSET,
                Zp0022Record.ZP_TXN_REF_OFFSET + Zp0022Record.ZP_TXN_REF_LEN).trim();
        String merchantId = line.substring(
                Zp0022Record.MERCHANT_ID_OFFSET,
                Zp0022Record.MERCHANT_ID_OFFSET + Zp0022Record.MERCHANT_ID_LEN).trim();
        LocalDate businessDate = LocalDate.parse(
                line.substring(
                        Zp0022Record.BUSINESS_DATE_OFFSET,
                        Zp0022Record.BUSINESS_DATE_OFFSET + Zp0022Record.BUSINESS_DATE_LEN),
                DATE_FMT);
        BigDecimal refundAmt = new BigDecimal(line.substring(
                Zp0022Record.REFUND_AMT_OFFSET,
                Zp0022Record.REFUND_AMT_OFFSET + Zp0022Record.REFUND_AMT_LEN).trim());
        String resultCode = line.substring(
                Zp0022Record.RESULT_CODE_OFFSET,
                Zp0022Record.RESULT_CODE_OFFSET + Zp0022Record.RESULT_CODE_LEN).trim();
        String resultMessage = line.substring(
                Zp0022Record.RESULT_MSG_OFFSET,
                Zp0022Record.RESULT_MSG_OFFSET + Zp0022Record.RESULT_MSG_LEN).trim();

        return new Zp0022Record(gmeTxnId, zpTxnRef, merchantId, businessDate,
                refundAmt, resultCode, resultMessage);
    }

    record TrailerCounts(int recordCount, BigDecimal controlSum) {}
}
