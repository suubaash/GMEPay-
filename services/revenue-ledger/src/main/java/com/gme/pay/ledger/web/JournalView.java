package com.gme.pay.ledger.web;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read-only view of a posted double-entry journal for {@code GET /v1/journals} (Admin UI).
 *
 * <p>Wire shape:
 * <pre>
 *   { "journalId": "...", "reference": "TXN-00001", "createdAt": "2026-07-03T…Z",
 *     "lines": [ { "account": "REVENUE_FX_MARGIN", "side": "CREDIT",
 *                  "amount": "12.34000000", "currency": "USD" }, … ] }
 * </pre>
 *
 * <p><b>Field sourcing (faithful to the schema, no derivation).</b>
 * <ul>
 *   <li>{@code createdAt} = {@code journals.posted_at} — the journal head's post timestamp.</li>
 *   <li>{@code side} = {@code ledger_entries.entry_type} verbatim — the stored DR/CR indicator
 *       ({@code "DEBIT"} / {@code "CREDIT"}). It is an explicit column, NOT inferred from the sign
 *       of {@code amount}; {@code amount} is always the non-negative magnitude.</li>
 *   <li>{@code currency} = {@code ledger_entries.currency} (ISO-4217).</li>
 * </ul>
 *
 * <p>Money rides as a decimal STRING per {@code docs/MONEY_CONVENTION.md} (avoids float drift on
 * the {@code NUMERIC(20,8)} column).
 */
public record JournalView(
        String journalId,
        String reference,
        Instant createdAt,
        List<Line> lines
) {

    /** One ledger line: account, DR/CR side, signed-magnitude amount, currency. */
    public record Line(
            String account,
            String side,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            String currency
    ) {}
}
