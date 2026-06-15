package com.gme.pay.settlement.parser;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for ZP0012 – ZeroPay <em>payment registration result</em> file.
 *
 * <p>ZP0012 is ZeroPay's response to a GME-submitted ZP0011 payment registration batch.
 * Each data line confirms or rejects one payment transaction.
 *
 * <h3>CSV layout (per ZeroPay Interface Spec §3.2 — TODO: confirm with ZeroPay IDD):</h3>
 * <pre>
 * HEADER  : "ZP0012," + YYYYMMDD + "," + sequence
 * DATA    : txnRef,schemeRef,amount,resultCode
 * TRAILER : "EOF," + count + "," + totalApprovedAmount
 * </pre>
 *
 * <p>Result code "0000" = approved. Any other code = rejected/error.
 *
 * <p>TODO: confirmed placeholder — CSV delimiter and exact field widths are unconfirmed.
 * Parsing is intentionally lenient (trim on all fields) to tolerate minor spec drift.
 */
@Component
public class ZP0012Parser {

    private static final String DELIMITER = ",";

    /**
     * Parse all lines of a ZP0012 result file.
     *
     * @param lines raw CSV text lines
     * @return list of {@link ZeroPayResultRecord}
     * @throws ZeroPayFileParseException if the file is malformed
     */
    public List<ZeroPayResultRecord> parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ZeroPayFileParseException("ZP0012 file is empty");
        }

        List<ZeroPayResultRecord> records = new ArrayList<>();
        boolean headerSeen = false;
        boolean trailerSeen = false;
        int dataCount = 0;
        BigDecimal approvedTotal = BigDecimal.ZERO;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }

            if (!headerSeen) {
                if (!line.startsWith("ZP0012")) {
                    throw new ZeroPayFileParseException(
                            "ZP0012: expected HEADER starting with 'ZP0012' at line " + (i + 1)
                            + ", got: " + line);
                }
                records.add(new ZeroPayResultRecord(
                        ZeroPayResultRecord.FileType.ZP0012,
                        ZeroPayResultRecord.RecordType.HEADER,
                        null, null, null, null, null, line));
                headerSeen = true;
                continue;
            }

            if (line.startsWith("EOF")) {
                trailerSeen = true;
                // TRAILER: EOF,count,totalApprovedAmount
                String[] parts = line.split(DELIMITER, -1);
                if (parts.length < 3) {
                    throw new ZeroPayFileParseException(
                            "ZP0012 TRAILER malformed at line " + (i + 1) + ": " + line);
                }
                try {
                    int expectedCount = Integer.parseInt(parts[1].trim());
                    BigDecimal expectedTotal = new BigDecimal(parts[2].trim());
                    if (expectedCount != dataCount) {
                        throw new ZeroPayFileParseException(
                                "ZP0012 TRAILER: record count mismatch — expected " + expectedCount
                                + " but saw " + dataCount);
                    }
                    if (approvedTotal.compareTo(expectedTotal) != 0) {
                        throw new ZeroPayFileParseException(
                                "ZP0012 TRAILER: approved total mismatch — expected " + expectedTotal
                                + " but computed " + approvedTotal);
                    }
                } catch (NumberFormatException e) {
                    throw new ZeroPayFileParseException(
                            "ZP0012 TRAILER numeric parse error at line " + (i + 1) + ": " + line, e);
                }
                records.add(new ZeroPayResultRecord(
                        ZeroPayResultRecord.FileType.ZP0012,
                        ZeroPayResultRecord.RecordType.TRAILER,
                        null, null, null, null, null, line));
                continue;
            }

            // DATA line: txnRef,schemeRef,amount,resultCode
            String[] parts = line.split(DELIMITER, -1);
            if (parts.length < 4) {
                throw new ZeroPayFileParseException(
                        "ZP0012 DATA line has fewer than 4 fields at line " + (i + 1) + ": " + line);
            }
            String txnRef = parts[0].trim();
            String schemeRef = parts[1].trim();
            BigDecimal amount;
            try {
                amount = new BigDecimal(parts[2].trim());
            } catch (NumberFormatException e) {
                throw new ZeroPayFileParseException(
                        "ZP0012 DATA amount not numeric at line " + (i + 1) + ": " + line, e);
            }
            String resultCode = parts[3].trim();

            if ("0000".equals(resultCode)) {
                approvedTotal = approvedTotal.add(amount);
            }
            dataCount++;

            records.add(new ZeroPayResultRecord(
                    ZeroPayResultRecord.FileType.ZP0012,
                    ZeroPayResultRecord.RecordType.DATA,
                    null, txnRef, schemeRef, amount, resultCode, line));
        }

        if (!headerSeen) {
            throw new ZeroPayFileParseException("ZP0012: no HEADER record found");
        }
        if (!trailerSeen) {
            throw new ZeroPayFileParseException("ZP0012: no TRAILER record found");
        }
        return records;
    }
}
