package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a ZP0011 payment-result file from fixed-width bytes back into domain records.
 *
 * <p>Validates header/trailer record counts and control sum on every parse.</p>
 */
public final class Zp0011FileParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");

    /**
     * Parses all detail records from a ZP0011 file.
     *
     * @param fileBytes UTF-8 bytes of the complete ZP0011 file
     * @return ordered list of detail records (excludes header and trailer lines)
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} if header/trailer
     *                      counts or the control sum do not match
     */
    public List<Zp0011Record> parse(byte[] fileBytes) {
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        if (lines.length < 2) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0011 file must have at least a header and trailer line");
        }

        Zp0011HeaderRecord header = parseHeader(lines[0]);
        Zp0011TrailerRecord trailer = parseTrailer(lines[lines.length - 1]);

        List<Zp0011Record> records = new ArrayList<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (line.charAt(0) != 'D') {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Unexpected record type '" + line.charAt(0) + "' at line " + (i + 1));
            }
            records.add(parseDetail(line));
        }

        // Validate record count
        if (records.size() != header.totalRecordCount()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Header record count " + header.totalRecordCount()
                            + " does not match actual detail count " + records.size());
        }

        // Validate control sum
        BigDecimal actualSum = records.stream()
                .map(Zp0011Record::payoutAmountKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (actualSum.compareTo(trailer.controlSum()) != 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Trailer control sum " + trailer.controlSum()
                            + " does not match computed sum " + actualSum);
        }

        return records;
    }

    // -----------------------------------------------------------------------
    // Package-private for testing
    // -----------------------------------------------------------------------

    Zp0011HeaderRecord parseHeader(String line) {
        if (line.length() < Zp0011HeaderRecord.RECORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0011 header line too short: " + line.length());
        }
        String fileType = line.substring(
                Zp0011HeaderRecord.FILE_TYPE_OFFSET,
                Zp0011HeaderRecord.FILE_TYPE_OFFSET + Zp0011HeaderRecord.FILE_TYPE_LEN).trim();
        if (!Zp0011HeaderRecord.FILE_TYPE_VALUE.equals(fileType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Expected ZP0011 file type, got: " + fileType);
        }
        LocalDate businessDate = LocalDate.parse(
                line.substring(
                        Zp0011HeaderRecord.BUSINESS_DATE_OFFSET,
                        Zp0011HeaderRecord.BUSINESS_DATE_OFFSET + Zp0011HeaderRecord.BUSINESS_DATE_LEN),
                DATE_FMT);
        String institutionCode = line.substring(
                Zp0011HeaderRecord.INSTITUTION_CODE_OFFSET,
                Zp0011HeaderRecord.INSTITUTION_CODE_OFFSET + Zp0011HeaderRecord.INSTITUTION_CODE_LEN).trim();
        int recordCount = Integer.parseInt(line.substring(
                Zp0011HeaderRecord.RECORD_COUNT_OFFSET,
                Zp0011HeaderRecord.RECORD_COUNT_OFFSET + Zp0011HeaderRecord.RECORD_COUNT_LEN).trim());
        BigDecimal totalAmount = new BigDecimal(line.substring(
                Zp0011HeaderRecord.TOTAL_AMOUNT_OFFSET,
                Zp0011HeaderRecord.TOTAL_AMOUNT_OFFSET + Zp0011HeaderRecord.TOTAL_AMOUNT_LEN).trim());
        return new Zp0011HeaderRecord(fileType, businessDate, institutionCode,
                recordCount, totalAmount);
    }

    Zp0011TrailerRecord parseTrailer(String line) {
        if (line.length() < Zp0011TrailerRecord.RECORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0011 trailer line too short: " + line.length());
        }
        if (line.charAt(0) != 'T') {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0011 trailer must start with 'T', got: " + line.charAt(0));
        }
        int recordCount = Integer.parseInt(line.substring(
                Zp0011TrailerRecord.RECORD_COUNT_OFFSET,
                Zp0011TrailerRecord.RECORD_COUNT_OFFSET + Zp0011TrailerRecord.RECORD_COUNT_LEN).trim());
        BigDecimal controlSum = new BigDecimal(line.substring(
                Zp0011TrailerRecord.CONTROL_SUM_OFFSET,
                Zp0011TrailerRecord.CONTROL_SUM_OFFSET + Zp0011TrailerRecord.CONTROL_SUM_LEN).trim());
        return new Zp0011TrailerRecord(recordCount, controlSum);
    }

    Zp0011Record parseDetail(String line) {
        if (line.length() < Zp0011Record.RECORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ZP0011 detail line too short: " + line.length());
        }
        String gmeTxnId = line.substring(
                Zp0011Record.GME_TXN_ID_OFFSET,
                Zp0011Record.GME_TXN_ID_OFFSET + Zp0011Record.GME_TXN_ID_LEN).trim();
        String zpTxnRef = line.substring(
                Zp0011Record.ZP_TXN_REF_OFFSET,
                Zp0011Record.ZP_TXN_REF_OFFSET + Zp0011Record.ZP_TXN_REF_LEN).trim();
        String merchantId = line.substring(
                Zp0011Record.MERCHANT_ID_OFFSET,
                Zp0011Record.MERCHANT_ID_OFFSET + Zp0011Record.MERCHANT_ID_LEN).trim();
        String qrCodeId = line.substring(
                Zp0011Record.QR_CODE_ID_OFFSET,
                Zp0011Record.QR_CODE_ID_OFFSET + Zp0011Record.QR_CODE_ID_LEN).trim();
        LocalDate txnDate = LocalDate.parse(
                line.substring(
                        Zp0011Record.TXN_DATE_OFFSET,
                        Zp0011Record.TXN_DATE_OFFSET + Zp0011Record.TXN_DATE_LEN),
                DATE_FMT);
        LocalTime txnTime = LocalTime.parse(
                line.substring(
                        Zp0011Record.TXN_TIME_OFFSET,
                        Zp0011Record.TXN_TIME_OFFSET + Zp0011Record.TXN_TIME_LEN),
                TIME_FMT);
        BigDecimal payoutAmt = new BigDecimal(line.substring(
                Zp0011Record.PAYOUT_AMT_OFFSET,
                Zp0011Record.PAYOUT_AMT_OFFSET + Zp0011Record.PAYOUT_AMT_LEN).trim());
        BigDecimal merchantFee = new BigDecimal(line.substring(
                Zp0011Record.MERCHANT_FEE_OFFSET,
                Zp0011Record.MERCHANT_FEE_OFFSET + Zp0011Record.MERCHANT_FEE_LEN).trim());
        BigDecimal vanFee = new BigDecimal(line.substring(
                Zp0011Record.VAN_FEE_OFFSET,
                Zp0011Record.VAN_FEE_OFFSET + Zp0011Record.VAN_FEE_LEN).trim());
        char partnerType = line.charAt(Zp0011Record.PARTNER_TYPE_OFFSET);
        String approvalCode = line.substring(
                Zp0011Record.APPROVAL_CODE_OFFSET,
                Zp0011Record.APPROVAL_CODE_OFFSET + Zp0011Record.APPROVAL_CODE_LEN).trim();
        char statusCode = line.charAt(Zp0011Record.STATUS_CODE_OFFSET);

        return new Zp0011Record(gmeTxnId, zpTxnRef, merchantId, qrCodeId, txnDate, txnTime,
                payoutAmt, merchantFee, vanFee, partnerType, approvalCode, statusCode);
    }
}
