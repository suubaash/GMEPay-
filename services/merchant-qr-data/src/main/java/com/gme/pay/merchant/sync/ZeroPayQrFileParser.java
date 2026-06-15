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
 * Parses ZeroPay QR-type files: ZP0043 (incremental), ZP0047 (franchise QR),
 * and ZP0053 (full QR list).
 *
 * <p><strong>File layout (pipe-delimited):</strong>
 * <pre>
 *   ZP0043 / ZP0047 (incremental — 4 columns):
 *     record_type | qr_code | merchant_id | status
 *       record_type: "QR" = register, "QD" = deactivate
 *
 *   ZP0053 (full list — 3 columns, no record_type column):
 *     qr_code | merchant_id | status
 * </pre>
 *
 * <p>Lines starting with {@code #} are treated as comments and skipped.
 * Blank lines are skipped silently.
 *
 * <p>Layout is an INTERNAL APPROXIMATION — see {@link ZeroPayFileType} Javadoc.
 */
@Component
public class ZeroPayQrFileParser {

    private static final Logger log = LoggerFactory.getLogger(ZeroPayQrFileParser.class);

    static final String DELIMITER = "|";
    static final int INCREMENTAL_COLUMNS = 4;
    static final int FULL_LIST_COLUMNS   = 3;

    /**
     * Parses a ZP0043 / ZP0047 incremental QR file.
     *
     * @param reader   character stream (caller closes)
     * @param filename source file name for log context
     * @return parsed QR rows; malformed rows are logged and skipped
     * @throws IOException if the reader fails
     */
    public ParseResult<ParsedQrRow> parseIncremental(Reader reader, String filename)
            throws IOException {
        return parse(reader, filename, true);
    }

    /**
     * Parses a ZP0053 full QR-list file.
     *
     * @param reader   character stream
     * @param filename source file name for log context
     * @return parsed QR rows; malformed rows are logged and skipped
     * @throws IOException if the reader fails
     */
    public ParseResult<ParsedQrRow> parseFullList(Reader reader, String filename)
            throws IOException {
        return parse(reader, filename, false);
    }

    private ParseResult<ParsedQrRow> parse(Reader reader, String filename,
                                            boolean incremental) throws IOException {
        List<ParsedQrRow> rows = new ArrayList<>();
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
                    ParsedQrRow row = parseLine(trimmed, lineNumber, incremental);
                    rows.add(row);
                } catch (IllegalArgumentException e) {
                    String msg = filename + ":" + lineNumber + " — " + e.getMessage();
                    log.warn("Skipping malformed QR row: {}", msg);
                    errors.add(msg);
                }
            }
        }
        return new ParseResult<>(rows, skipped, errors);
    }

    private ParsedQrRow parseLine(String line, int lineNumber, boolean incremental) {
        String[] parts = line.split("\\" + DELIMITER, -1);
        int expectedCols = incremental ? INCREMENTAL_COLUMNS : FULL_LIST_COLUMNS;

        if (parts.length < expectedCols) {
            throw new IllegalArgumentException(
                    "expected " + expectedCols + " columns but got " + parts.length
                    + " [line: " + line + "]");
        }

        if (incremental) {
            // Col: record_type | qr_code | merchant_id | status
            String recordType = clean(parts[0]);
            if (recordType.isEmpty()) {
                throw new IllegalArgumentException("record_type is blank [line: " + line + "]");
            }
            String qrCode = requireNonBlank(parts[1], "qr_code", line);
            return new ParsedQrRow(
                    recordType,
                    qrCode,
                    clean(parts[2]),
                    clean(parts[3]));
        } else {
            // Full list — no record_type
            // Col: qr_code | merchant_id | status
            String qrCode = requireNonBlank(parts[0], "qr_code", line);
            return new ParsedQrRow(
                    null,
                    qrCode,
                    clean(parts[1]),
                    clean(parts[2]));
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
