package com.gme.pay.bff.client;

import java.time.LocalDate;

/**
 * Read-only statement export surface. Production implementation will call
 * {@code reporting-compliance} for the canonical CSV; Phase-1 default is an
 * in-memory stub generating a deterministic CSV so the Partner Portal
 * Statement page can offer downloads without booting reporting-compliance.
 *
 * <p>The CSV columns are: {@code txnId, partnerId, status, amount, currency,
 * createdAt}. The first row is the header; subsequent rows are the
 * transactions in the requested date range.
 */
public interface StatementClient {

    /**
     * Returns the CSV bytes for {@code partnerId} restricted to transactions
     * created within the inclusive range {@code [from, to]}. Always returns a
     * non-null array; when no transactions match the range, the array
     * contains only the header row.
     */
    byte[] exportCsv(String partnerId, LocalDate from, LocalDate to);
}
