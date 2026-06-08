package com.gme.pay.reporting.service;

import com.gme.pay.reporting.domain.CommittedTransaction;

import java.time.LocalDate;
import java.util.List;

/**
 * Interface for the transaction-mgmt service API.
 * This service NEVER reads transaction-mgmt's database directly (MSA rule).
 *
 * <p>In production this will be implemented as an HTTP client calling
 * {@code GET /v1/transactions?from=...&to=...&partnerId=...}.
 * In tests it is stubbed with WireMock or an in-process mock.
 */
public interface TransactionClient {

    /**
     * Fetches committed cross-border transactions for a date range.
     * Domestic/same-currency transactions may be included; the caller
     * is responsible for filtering them before BOK report generation.
     *
     * @param from       inclusive start date (UTC)
     * @param to         inclusive end date (UTC)
     * @param partnerId  filter by partner; null = all partners
     * @return list of committed transactions in the date range
     */
    List<CommittedTransaction> fetchCommitted(LocalDate from, LocalDate to, Long partnerId);
}
