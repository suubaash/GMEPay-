package com.gme.pay.contracts.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical payload for the {@code transaction.committed} domain event emitted by transaction-mgmt at
 * CommitTransaction, on topic {@code gmepay.transaction.committed}. Carries the rate-locked FX fields
 * the canonical {@code GET /v1/transactions} omits, so downstream consumers (reporting-compliance
 * FX1015, settlement-reconciliation, scheme-adapter, revenue-ledger) can project committed cross-border
 * txns. This is the event sibling of the {@code CommittedFxView} query projection.
 *
 * <p><b>Convention.</b> camelCase fields; money + rates ride as decimal STRINGs per
 * {@code docs/MONEY_CONVENTION.md}. Field names mirror reporting-compliance's
 * {@code CommittedTransaction} value object so a consumer can swap its local type for this with minimal
 * churn. (IR-txn-1 wrote these snake_case — camelCase chosen here for consistency with the rest of the
 * contract DTOs and the {@code CommittedFxView} projection.)
 *
 * <ul>
 *   <li>{@code offerRateColl} — {@code send_amount / (collection_usd - collection_margin_usd)};
 *       BOK FX1015 field #14. NULL for same-currency short-circuit.</li>
 *   <li>{@code crossRate} — {@code target_payout / send_amount}. NULL for same-currency short-circuit.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransactionCommittedPayload(
        String eventType,
        String aggregateId,
        String txnRef,
        Instant occurredAt,
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

    /** Canonical eventType string; drives topic {@code gmepay.transaction.committed}. */
    public static final String EVENT_TYPE = "transaction.committed";
}
