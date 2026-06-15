package com.gme.pay.settlement.parser;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for ZP0062 – ZeroPay <em>morning</em> settlement result file.
 *
 * <h3>Fixed-width layout (per ZeroPay Interface Spec §4.2):</h3>
 * <pre>
 * HEADER  : "ZP0062" + YYYYMMDD(8) + sequence(3)              = 17 chars
 * DATA    : merchantId(16, left-padded) + amount(16, zero-padded, unit: KRW)
 * TRAILER : "EOF" + dataRecordCount(10, zero-padded) + totalAmount(16, zero-padded)
 * </pre>
 *
 * <p>TODO: confirmed placeholder — the actual spec column widths are not yet finalised
 * as of Phase 2a (calendar out-of-scope). The layout above is a best-effort derivation
 * from the sample fixture {@code fixtures/ZP0062_sample.txt} and will be corrected when
 * ZeroPay supplies the final IDD document.
 */
@Component
public class ZP0062Parser {

    // Field widths derived from ZeroPay sample file
    private static final int MERCHANT_ID_LEN = 16;
    private static final int AMOUNT_LEN = 16;
    private static final int DATA_LINE_LEN = MERCHANT_ID_LEN + AMOUNT_LEN; // 32

    /**
     * Parse all lines of a ZP0062 result file.
     *
     * @param lines raw text lines (no trailing newline on each)
     * @return list of {@link ZeroPayResultRecord} (HEADER + DATA rows + TRAILER)
     * @throws ZeroPayFileParseException if the file is malformed
     */
    public List<ZeroPayResultRecord> parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ZeroPayFileParseException("ZP0062 file is empty");
        }

        List<ZeroPayResultRecord> records = new ArrayList<>();
        boolean headerSeen = false;
        boolean trailerSeen = false;
        int dataCount = 0;
        BigDecimal runningTotal = BigDecimal.ZERO;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue; // skip blank lines (trailing newline artefact)
            }

            if (!headerSeen) {
                // First non-blank line must be the header
                if (!line.startsWith("ZP0062")) {
                    throw new ZeroPayFileParseException(
                            "ZP0062: expected HEADER starting with 'ZP0062' at line " + (i + 1)
                            + ", got: " + line);
                }
                records.add(new ZeroPayResultRecord(
                        ZeroPayResultRecord.FileType.ZP0062,
                        ZeroPayResultRecord.RecordType.HEADER,
                        null, null, null, null, null, line));
                headerSeen = true;
                continue;
            }

            if (line.startsWith("EOF")) {
                // TRAILER: "EOF" + count(10) + totalAmount(16)
                trailerSeen = true;
                try {
                    int expectedCount = Integer.parseInt(line.substring(3, 13).trim());
                    BigDecimal expectedTotal = new BigDecimal(line.substring(13).trim());
                    if (expectedCount != dataCount) {
                        throw new ZeroPayFileParseException(
                                "ZP0062 TRAILER: record count mismatch — expected " + expectedCount
                                + " but saw " + dataCount);
                    }
                    if (runningTotal.compareTo(expectedTotal) != 0) {
                        throw new ZeroPayFileParseException(
                                "ZP0062 TRAILER: total amount mismatch — expected " + expectedTotal
                                + " but computed " + runningTotal);
                    }
                } catch (StringIndexOutOfBoundsException | NumberFormatException e) {
                    throw new ZeroPayFileParseException(
                            "ZP0062 TRAILER malformed at line " + (i + 1) + ": " + line, e);
                }
                records.add(new ZeroPayResultRecord(
                        ZeroPayResultRecord.FileType.ZP0062,
                        ZeroPayResultRecord.RecordType.TRAILER,
                        null, null, null, null, null, line));
                continue;
            }

            // DATA line
            if (line.length() < DATA_LINE_LEN) {
                throw new ZeroPayFileParseException(
                        "ZP0062 DATA line too short at line " + (i + 1)
                        + " (expected " + DATA_LINE_LEN + " chars): " + line);
            }
            String merchantId = line.substring(0, MERCHANT_ID_LEN).trim();
            BigDecimal amount;
            try {
                amount = new BigDecimal(line.substring(MERCHANT_ID_LEN, MERCHANT_ID_LEN + AMOUNT_LEN).trim());
            } catch (NumberFormatException e) {
                throw new ZeroPayFileParseException(
                        "ZP0062 DATA amount not numeric at line " + (i + 1) + ": " + line, e);
            }
            runningTotal = runningTotal.add(amount);
            dataCount++;
            records.add(new ZeroPayResultRecord(
                    ZeroPayResultRecord.FileType.ZP0062,
                    ZeroPayResultRecord.RecordType.DATA,
                    merchantId, null, null, amount, null, line));
        }

        if (!headerSeen) {
            throw new ZeroPayFileParseException("ZP0062: no HEADER record found");
        }
        if (!trailerSeen) {
            throw new ZeroPayFileParseException("ZP0062: no TRAILER record found");
        }
        return records;
    }
}
