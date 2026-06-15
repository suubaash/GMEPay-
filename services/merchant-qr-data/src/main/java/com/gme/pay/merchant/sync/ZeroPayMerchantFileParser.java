package com.gme.pay.merchant.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses ZeroPay merchant-type files: ZP0041 (incremental), ZP0045 (franchise),
 * and ZP0051 (full list).
 *
 * <p><strong>File layout (pipe-delimited):</strong>
 * <pre>
 *   ZP0041 / ZP0045 (incremental — 10 columns):
 *     record_type | merchant_id | name | merchant_type | fee_type |
 *     status | payout_currency | scheme_id | city | mcc
 *
 *   ZP0051 (full list — 9 columns, no record_type column):
 *     merchant_id | name | merchant_type | fee_type |
 *     status | payout_currency | scheme_id | city | mcc
 * </pre>
 *
 * <p>Lines starting with {@code #} are treated as comments and skipped.
 * Blank lines are skipped silently.
 *
 * <p>Layout is an INTERNAL APPROXIMATION — see {@link ZeroPayFileType} Javadoc.
 * Fields marked TODO(spec) must be validated against the final ZeroPay Merchant
 * Data Interface Specification before production use.
 */
@Component
public class ZeroPayMerchantFileParser {

    private static final Logger log = LoggerFactory.getLogger(ZeroPayMerchantFileParser.class);

    static final String DELIMITER = "|";
    static final int INCREMENTAL_COLUMNS = 10;
    static final int FULL_LIST_COLUMNS   = 9;

    /**
     * Parses a ZP0041 / ZP0045 incremental merchant file.
     *
     * @param reader     character stream of the file (caller is responsible for closing)
     * @param filename   source file name used in log messages
     * @return list of parsed merchant rows; malformed rows are logged and skipped
     * @throws IOException if the underlying reader fails
     */
    public ParseResult<ParsedMerchantRow> parseIncremental(Reader reader, String filename)
            throws IOException {
        return parse(reader, filename, true);
    }

    /**
     * Parses a ZP0051 full merchant-list file.
     *
     * @param reader   character stream of the file
     * @param filename source file name for log context
     * @return parsed rows; malformed rows are logged and skipped
     * @throws IOException if the reader fails
     */
    public ParseResult<ParsedMerchantRow> parseFullList(Reader reader, String filename)
            throws IOException {
        return parse(reader, filename, false);
    }

    private ParseResult<ParsedMerchantRow> parse(Reader reader, String filename,
                                                  boolean incremental) throws IOException {
        List<ParsedMerchantRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        int lineNumber = 0;

        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    skipped++;
                    continue;
                }
                try {
                    ParsedMerchantRow row = parseLine(trimmed, filename, lineNumber, incremental);
                    rows.add(row);
                } catch (IllegalArgumentException e) {
                    String msg = filename + ":" + lineNumber + " — " + e.getMessage();
                    log.warn("Skipping malformed merchant row: {}", msg);
                    errors.add(msg);
                }
            }
        }
        return new ParseResult<>(rows, skipped, errors);
    }

    private ParsedMerchantRow parseLine(String line, String filename, int lineNumber,
                                        boolean incremental) {
        String[] parts = line.split("\\" + DELIMITER, -1);
        int expectedCols = incremental ? INCREMENTAL_COLUMNS : FULL_LIST_COLUMNS;

        if (parts.length < expectedCols) {
            throw new IllegalArgumentException(
                    "expected " + expectedCols + " columns but got " + parts.length
                    + " [line: " + line + "]");
        }

        if (incremental) {
            // Col: record_type | merchant_id | name | merchant_type | fee_type |
            //      status | payout_currency | scheme_id | city | mcc
            String recordType = clean(parts[0]);
            if (recordType.isEmpty()) {
                throw new IllegalArgumentException("record_type is blank [line: " + line + "]");
            }
            String merchantId = requireNonBlank(parts[1], "merchant_id", line);
            return new ParsedMerchantRow(
                    recordType,
                    merchantId,
                    clean(parts[2]),
                    clean(parts[3]),
                    clean(parts[4]),
                    clean(parts[5]),
                    clean(parts[6]),
                    clean(parts[7]),
                    clean(parts[8]),
                    clean(parts[9]));
        } else {
            // Full list — no record_type column
            // Col: merchant_id | name | merchant_type | fee_type |
            //      status | payout_currency | scheme_id | city | mcc
            String merchantId = requireNonBlank(parts[0], "merchant_id", line);
            return new ParsedMerchantRow(
                    null,
                    merchantId,
                    clean(parts[1]),
                    clean(parts[2]),
                    clean(parts[3]),
                    clean(parts[4]),
                    clean(parts[5]),
                    clean(parts[6]),
                    clean(parts[7]),
                    clean(parts[8]));
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String requireNonBlank(String value, String fieldName, String line) {
        String v = clean(value);
        if (v.isEmpty()) {
            throw new IllegalArgumentException(
                    "required field '" + fieldName + "' is blank [line: " + line + "]");
        }
        return v;
    }
}
