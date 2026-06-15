package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One deduction entry in a partner's prefunding balance deduction history (UC-10-01).
 *
 * <p>Returned as part of {@link BalanceView#recentDeductions()} — a short list of the
 * most recent prefunding deductions so the partner can correlate balance movements with
 * individual transactions.
 *
 * <ul>
 *   <li>{@code amountUsd} — USD amount deducted from the prefunding balance.
 *       Serialized as a decimal STRING on the wire per {@code docs/MONEY_CONVENTION.md}
 *       (never floating-point JSON). Scale-8, BigDecimal.</li>
 *   <li>{@code at}        — UTC instant the deduction was applied (KST displayed by the UI).</li>
 *   <li>{@code txnRef}    — transaction reference that caused the deduction (ties back to
 *       the transaction the partner can look up under UC-10-02/03).</li>
 * </ul>
 *
 * <p>{@code @JsonInclude(ALWAYS)} keeps null fields explicit on the wire — consistent
 * with the rest of the canonical contract DTOs (e.g. {@link PartnerView}, {@link BalanceView}).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BalanceDeductionEntry(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amountUsd,
        Instant at,
        String txnRef) {
}
