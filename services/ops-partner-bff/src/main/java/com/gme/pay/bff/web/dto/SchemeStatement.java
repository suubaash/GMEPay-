package com.gme.pay.bff.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * QR-scheme reconciliation statement (Goal #6). GME produces this so a QR-scheme partner
 * (e.g. ZEROPAY, NEPAL) can reconcile the transactions GME recorded for their scheme against
 * their own records. Admin-facing (GME exports/hands it to the scheme); read-only.
 *
 * <p>Shape:
 * <pre>
 * { schemeId, window:{from,to},
 *   totals:[ { currency, count, gross } ],           // per-currency over the FULL window
 *   items:[ { txnRef, occurredAt, merchantId, partnerId, amount, currency, status } ], // this page, newest-first
 *   page, size, total }
 * </pre>
 *
 * <p>Money ({@code gross}, {@code amount}) rides as {@link BigDecimal} — serialized as decimal
 * strings on the wire (MONEY_CONVENTION.md); the UI reads them as strings (no JS Number cast).
 *
 * @param schemeId the QR scheme identity the statement scopes to (e.g. "ZEROPAY")
 * @param window   the resolved [from, to) window the statement covers
 * @param totals   per-currency count + summed amount over the WHOLE window (not just this page)
 * @param items    this page of the scheme's transactions, newest-first
 * @param page     zero-based page index returned
 * @param size     page size returned
 * @param total    total number of the scheme's transactions in the window
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemeStatement(
        String schemeId,
        Window window,
        List<CurrencyTotal> totals,
        List<Item> items,
        int page,
        int size,
        long total) {

    /** The resolved statement window. */
    public record Window(Instant from, Instant to) {}

    /** Per-currency aggregate over the full window. */
    public record CurrencyTotal(
            String currency,
            long count,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal gross) {}

    /** One transaction row on the statement page. */
    public record Item(
            String txnRef,
            Instant occurredAt,
            String merchantId,
            String partnerId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            String currency,
            String status) {}
}
