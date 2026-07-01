package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical read DTO for one committed cross-border transaction with its rate-locked FX fields —
 * the projection transaction-mgmt exposes (e.g. {@code GET /v1/transactions/fx-committed}) and that
 * reporting-compliance (BOK FX1015), settlement-reconciliation, and scheme-adapter consume. The
 * canonical {@code GET /v1/transactions} omits the margin-derived fields; this view carries them.
 *
 * <p>Field names mirror reporting-compliance's local {@code CommittedTransaction} value object so a
 * consumer can swap its local type for this with minimal churn. {@code direction} is the wire string
 * ({@code INBOUND}/{@code OUTBOUND}/{@code DOMESTIC}/{@code HUB}) rather than a lib enum, to avoid
 * coupling consumers to a particular enum.
 *
 * <ul>
 *   <li>{@code offerRateColl} — {@code send_amount / (collection_usd - collection_margin_usd)};
 *       BOK FX1015 field #14. NULL for same-currency short-circuit.</li>
 *   <li>{@code crossRate} — {@code target_payout / send_amount}. NULL for same-currency short-circuit.</li>
 *   <li>Money/rate fields ride as decimal STRINGs per {@code docs/MONEY_CONVENTION.md}.</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} keeps null fields explicit on the wire, consistent with the other
 * canonical view DTOs.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CommittedFxView(
        long txnId,
        String txnRef,
        long partnerId,
        String direction,
        boolean sameCcyShortcircuit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal offerRateColl,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal crossRate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectionAmount,
        String collectionCcy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payoutAmount,
        String payoutCcy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal usdAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectionMarginUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payoutMarginUsd,
        Instant committedAt) {
}
