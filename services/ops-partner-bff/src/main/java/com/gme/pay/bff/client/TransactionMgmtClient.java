package com.gme.pay.bff.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only view of transaction-mgmt. Production implementation calls
 * {@code GET /v1/transactions/{id}} and a list endpoint; the Phase-1 default is
 * an in-memory stub.
 *
 * <p>Phase C2 adds a filtered, paginated {@link #list(Filter)} for the Admin UI
 * transactions search page.
 */
public interface TransactionMgmtClient {

    /** Fetch a single transaction by id; returns {@code null} when unknown. */
    TransactionSummary getTransaction(String txnId);

    /**
     * Fetch the {@code limit} most recent transactions across all partners
     * (Admin UI) or for one partner (Portal UI).
     *
     * @param partnerId optional — null means all partners
     * @param limit     maximum number of rows to return
     */
    List<TransactionSummary> recent(String partnerId, int limit);

    /**
     * Filtered + paginated transaction search. Production calls
     * {@code GET /v1/transactions?...}; Phase-1 stub filters the in-memory list.
     */
    Page<TransactionSummary> list(Filter filter);

    record TransactionSummary(
            String txnId,
            String partnerId,
            String state,
            BigDecimal amount,
            String currency,
            Instant committedAt
    ) {}

    /**
     * Filter shape for {@link #list(Filter)}. All criterion fields are optional;
     * a {@code null} means "do not filter on this field". Page is 0-indexed.
     */
    record Filter(
            String partnerId,
            String schemeId,
            String state,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size
    ) {}

    /**
     * Generic page envelope used by paginated client methods. The fields mirror
     * the BFF's {@code Page<T>} DTO so the wire shape passes through unchanged.
     */
    record Page<T>(List<T> content, int page, int size, long total) {}
}
