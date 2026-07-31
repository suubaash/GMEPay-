package com.gme.pay.settlement.tieout;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for GET /v1/settlements/tie-out — the daily cent-for-cent reconciliation proving
 * payments, settlement batches, and the revenue ledger agree for one business date.
 *
 * <p>All money fields are whole-KRW {@link BigDecimal}s serialized as decimal STRINGs on the wire
 * ({@code docs/MONEY_CONVENTION.md}, same {@code @JsonFormat(STRING)} pattern as the canonical
 * lib-api-contracts DTOs). Consumers MUST NOT cast to {@code double}.
 *
 * <p>Field semantics (see {@link TieOutService} for the money reasoning):
 * <ul>
 *   <li>{@code txnGrossKrw}      — Σ KRW payouts of the date's APPROVED transactions
 *                                  (unbatched via transaction-mgmt + already-settled lines);</li>
 *   <li>{@code settledNetKrw}    — Σ {@code settlement_batches.net_settlement_amount};</li>
 *   <li>{@code settledFeeKrw}    — Σ {@code settlement_batches.merchant_fee_total};</li>
 *   <li>{@code settledRefundKrw} — Σ magnitudes of negative {@code settlement_lines} (clawbacks);</li>
 *   <li>{@code settlementBalanced} — net + fee + refund == the batches' positive line sum
 *                                    (the gross = net + fee + refund invariant);</li>
 *   <li>{@code ledgerFeeKrw}/{@code ledgerDeltaKrw} — present only when {@code ledgerAvailable};
 *                                    delta = settledFee − ledgerFee (0 = ledger agrees);</li>
 *   <li>{@code tiedOut}          — settlementBalanced AND (ledger unavailable OR delta == 0).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TieOutReport(
        LocalDate businessDate,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal txnGrossKrw,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal settledNetKrw,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal settledFeeKrw,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal settledRefundKrw,

        boolean settlementBalanced,

        boolean ledgerAvailable,

        /** Null (omitted) when the ledger figure is unavailable. */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal ledgerFeeKrw,

        /** settledFeeKrw − ledgerFeeKrw; null (omitted) when the ledger figure is unavailable. */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal ledgerDeltaKrw,

        boolean tiedOut
) {}
