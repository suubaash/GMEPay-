package com.gme.pay.merchant.sync;

import java.util.List;

/**
 * Result of parsing a single ZeroPay batch file.
 *
 * @param <R>      Row type ({@link ParsedMerchantRow} or {@link ParsedQrRow})
 * @param rows     Successfully parsed rows
 * @param skipped  Count of blank/comment lines skipped without error
 * @param errors   Error messages for rows that failed to parse (logged and skipped)
 */
public record ParseResult<R>(
        List<R> rows,
        int skipped,
        List<String> errors) {

    /** Returns {@code true} when no parsing errors occurred. */
    public boolean isClean() {
        return errors.isEmpty();
    }

    /** Total lines consumed (rows + skipped + errors). */
    public int totalLines() {
        return rows.size() + skipped + errors.size();
    }
}
