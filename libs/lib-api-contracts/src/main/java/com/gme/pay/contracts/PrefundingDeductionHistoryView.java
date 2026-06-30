package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Read DTO for {@code GET /v1/prefunding/{code}/deductions?limit=N} — the prefunding deduction history
 * payment-executor needs for balance {@code ?include_history=true} (IR-pe-2). Wraps a bounded list of
 * {@link BalanceDeductionEntry} (already the canonical per-deduction shape: {@code amountUsd}, {@code at},
 * {@code txnRef}) so no new per-row type is introduced.
 *
 * <ul>
 *   <li>{@code partnerCode} — the partner code the history is for.</li>
 *   <li>{@code entries} — most-recent-first, capped at the requested {@code limit}.</li>
 *   <li>{@code limit} — the cap that was applied (echoed for the caller).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrefundingDeductionHistoryView(
        String partnerCode,
        List<BalanceDeductionEntry> entries,
        int limit) {
}
