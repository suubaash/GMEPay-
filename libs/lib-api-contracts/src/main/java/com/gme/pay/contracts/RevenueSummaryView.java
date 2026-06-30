package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Canonical read DTO for {@code GET /v1/revenue} — aggregate revenue for a partner over a date range.
 * Mirrors revenue-ledger's service-local {@code RevenueSummaryResponse} so ops-partner-bff and the
 * reporting revenue board can bind one shared type. Money rides as decimal STRINGs per
 * {@code docs/MONEY_CONVENTION.md}.
 *
 * <p>{@code totalRoundingUsd} is the additive field requested at integration (revenue-ledger IR-3):
 * cumulative settlement-rounding residual in USD. Nullable on the wire (older producers may omit it);
 * consumers should treat {@code null} as zero.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RevenueSummaryView(
        long partnerId,
        long schemeId,
        LocalDate startDate,
        LocalDate endDate,
        long txnCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalFxMarginUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalServiceChargeAmount,
        String serviceChargeCcy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalRoundingUsd) {
}
