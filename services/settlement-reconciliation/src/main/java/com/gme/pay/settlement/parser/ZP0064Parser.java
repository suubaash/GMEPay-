package com.gme.pay.settlement.parser;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for ZP0064 – ZeroPay <em>afternoon</em> settlement result file.
 *
 * <p>Identical layout to {@link ZP0062Parser} except the file-type marker is {@code ZP0064}.
 * Both represent per-merchant net settlement amounts but for different cut-off windows:
 * <ul>
 *   <li>ZP0062 – morning window (~10:00 KST)</li>
 *   <li>ZP0064 – afternoon window (~19:00 KST)</li>
 * </ul>
 *
 * <h3>Fixed-width layout (per ZeroPay Interface Spec §4.4):</h3>
 * <pre>
 * HEADER  : "ZP0064" + YYYYMMDD(8) + sequence(3)
 * DATA    : merchantId(16) + amount(16, zero-padded KRW)
 * TRAILER : "EOF" + count(10) + totalAmount(16)
 * </pre>
 *
 * <p>TODO: confirmed placeholder — layout mirrors ZP0062; pending final ZeroPay IDD.
 */
@Component
public class ZP0064Parser {

    private static final int MERCHANT_ID_LEN = 16;
    private static final int AMOUNT_LEN = 16;
    private static final int DATA_LINE_LEN = MERCHANT_ID_LEN + AMOUNT_LEN;

    /**
     * Parse all lines of a ZP0064 result file.
     *
     * @param lines raw text lines
     * @return list of {@link ZeroPayResultRecord}
     * @throws ZeroPayFileParseException if the file is malformed
     */
    public List<ZeroPayResultRecord> parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ZeroPayFileParseException("ZP0064 file is empty");
        }

        List<ZeroPayResultRecord> records = new ArrayList<>();
        boolean headerSeen = false;
        boolean trailerSeen = false;
        int dataCount = 0;
        BigDecimal runningTotal = BigDecimal.ZERO;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }

            if (!headerSeen) {
                if (!line.startsWith("ZP0064")) {
                    throw new ZeroPayFileParseException(
                            "ZP0064: expected HEADER starting with 'ZP0064' at line " + (i + 1)
                            + ", got: " + line);
                }
                records.add(new ZeroPayResultRecord(
                        ZeroPayResultRecord.FileType.ZP0064,
                        ZeroPayResultRecord.RecordType.HEADER,
                        null, null, null, null, null, line));
                headerSeen = true;
                continue;
            }

            if (line.startsWith("EOF")) {
                trailerSeen = true;
                try {
                    int expectedCount = Integer.parseInt(line.substring(3, 13).trim());
                    BigDecimal expectedTotal = new BigDecimal(line.substring(13).trim());
                    if (expectedCount != dataCount) {
                        throw new ZeroPayFileParseException(
                                "ZP0064 TRAILER: record count mismatch — expected " + expectedCount
                                + " but saw " + dataCount);
                    }
                    if (runningTotal.compareTo(expectedTotal) != 0) {
                        throw new ZeroPayFileParseException(
                                "ZP0064 TRAILER: total amount mismatch — expected " + expectedTotal
                                + " but computed " + runningTotal);
                    }
                } catch (StringIndexOutOfBoundsException | NumberFormatException e) {
                    throw new ZeroPayFileParseException(
                            "ZP0064 TRAILER malformed at line " + (i + 1) + ": " + line, e);
                }
                records.add(new ZeroPayResultRecord(
                        ZeroPayResultRecord.FileType.ZP0064,
                        ZeroPayResultRecord.RecordType.TRAILER,
                        null, null, null, null, null, line));
                continue;
            }

            // DATA line
            if (line.length() < DATA_LINE_LEN) {
                throw new ZeroPayFileParseException(
                        "ZP0064 DATA line too short at line " + (i + 1)
                        + " (expected " + DATA_LINE_LEN + " chars): " + line);
            }
            String merchantId = line.substring(0, MERCHANT_ID_LEN).trim();
            BigDecimal amount;
            try {
                amount = new BigDecimal(line.substring(MERCHANT_ID_LEN, MERCHANT_ID_LEN + AMOUNT_LEN).trim());
            } catch (NumberFormatException e) {
                throw new ZeroPayFileParseException(
                        "ZP0064 DATA amount not numeric at line " + (i + 1) + ": " + line, e);
            }
            runningTotal = runningTotal.add(amount);
            dataCount++;
            records.add(new ZeroPayResultRecord(
                    ZeroPayResultRecord.FileType.ZP0064,
                    ZeroPayResultRecord.RecordType.DATA,
                    merchantId, null, null, amount, null, line));
        }

        if (!headerSeen) {
            throw new ZeroPayFileParseException("ZP0064: no HEADER record found");
        }
        if (!trailerSeen) {
            throw new ZeroPayFileParseException("ZP0064: no TRAILER record found");
        }
        return records;
    }
}
