package com.gme.pay.settlement.corridor;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.settlement.persistence.CorridorReconSummaryEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The daily cross-border reconciliation summary as finance reads it (GAP T2-2).
 *
 * <p>Money is BigDecimal-as-string per {@code docs/MONEY_CONVENTION.md}. The two numbers that matter
 * for the CFO risk are {@link #rateBasisVarianceUsd} (this day, signed) and
 * {@link #cumulativeVarianceUsd} (running signed total) — a persistent negative drift is the
 * rate-basis loss that previously accrued with no owner.
 *
 * @param settlementDate         business date
 * @param scheme                 recon lane (SENDMN)
 * @param corridor               e.g. {@code KRW->MNT}
 * @param batchId                recon run id; the {@code batchId} filter for its breaks in
 *                               {@code GET /v1/settlement/exceptions}
 * @param txnCount               transactions seen on any leg
 * @param chargedKrw             Σ KRW charged to wallets (amount + service fee)
 * @param localPaid              Σ local currency paid to merchants
 * @param localCurrency          the local currency (MNT)
 * @param usdDeducted            Σ USD taken off the partner float (hub's USD/KRW basis)
 * @param usdOwedScheme          Σ USD owed the scheme at ITS registered rate
 * @param rateBasisVarianceUsd   SIGNED {@code usdDeducted − usdOwedScheme} for the day
 * @param cumulativeVarianceUsd  SIGNED running total through this date
 * @param fallbackRateBasisCount transactions whose USD deduction was priced off the hub's hardcoded
 *                               USD/KRW fallback rather than a live rate
 * @param breakCount             open recon breaks raised for the day
 * @param breakValueUsd          Σ absolute USD value of those breaks
 * @param schemeFeedAvailable    whether a REAL partner settlement file backed this tie-out. FALSE
 *                               today: SendMN publishes no recon file format (external gate O4), so
 *                               the tie-out compares only GME-owned sources
 * @param generatedAt            when the summary was produced
 */
public record CorridorReconSummaryResponse(
        LocalDate settlementDate,
        String scheme,
        String corridor,
        String batchId,
        int txnCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal chargedKrw,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal localPaid,
        String localCurrency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal usdDeducted,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal usdOwedScheme,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rateBasisVarianceUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cumulativeVarianceUsd,
        int fallbackRateBasisCount,
        int breakCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal breakValueUsd,
        boolean schemeFeedAvailable,
        Instant generatedAt) {

    /** Map the persisted summary row to the wire DTO. */
    public static CorridorReconSummaryResponse from(CorridorReconSummaryEntity e) {
        return new CorridorReconSummaryResponse(
                e.getSettlementDate(),
                e.getScheme(),
                e.getCorridor(),
                e.getBatchId(),
                e.getTxnCount(),
                e.getChargedKrw(),
                e.getLocalPaid(),
                e.getLocalCurrency(),
                e.getUsdDeducted(),
                e.getUsdOwedScheme(),
                e.getRateBasisVarianceUsd(),
                e.getCumulativeVarianceUsd(),
                e.getFallbackRateBasisCount(),
                e.getBreakCount(),
                e.getBreakValueUsd(),
                e.isSchemeFeedAvailable(),
                e.getGeneratedAt());
    }
}
