package com.gme.pay.ledger.revenue;

import com.gme.pay.ledger.domain.ledger.JournalStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Read-only query service for revenue record aggregates used by the Admin Portal (PRD-07 §11.3.2).
 * All methods are pure reads with no side-effects.
 */
@Service
public class RevenueRecordService {

    /** USD is the settlement/reporting currency the rounding total is reported in (see {@link #getRoundingTotalUsd}). */
    private static final String REPORTING_CCY = "USD";

    private final RevenueRecordStore store;
    private final JournalStore journalStore;

    public RevenueRecordService(RevenueRecordStore store, JournalStore journalStore) {
        this.store = Objects.requireNonNull(store);
        this.journalStore = Objects.requireNonNull(journalStore);
    }

    /**
     * Aggregate FX margin and service-charge totals for one partner over a date range.
     *
     * @throws IllegalArgumentException if start > end
     */
    public RevenueAggregate getRevenueByPartner(long partnerId, LocalDate start, LocalDate end) {
        validateRange(start, end);
        BigDecimal fxMargin = store.sumFxMarginUsdByPartnerAndDateRange(partnerId, start, end);
        BigDecimal serviceCharge = store.sumServiceChargeByPartnerAndDateRange(partnerId, start, end);
        long txnCount = store.countByPartnerAndDateRange(partnerId, start, end);
        // A partner normally bills service charge in a single currency; report it (default USD when
        // the partner has no rows in range, matching the fxMargin USD denomination).
        String serviceChargeCcy = store.serviceChargeCcyByPartnerAndDateRange(partnerId, start, end);
        // schemeId is 0 on the per-partner aggregate (it spans all of the partner's schemes); the
        // per-scheme breakdown is served separately via getServiceChargeByScheme.
        return new RevenueAggregate(partnerId, 0L, txnCount,
                coalesce(fxMargin), coalesce(serviceCharge),
                serviceChargeCcy == null ? "USD" : serviceChargeCcy);
    }

    /**
     * Aggregate service-charge totals for one scheme over a date range.
     *
     * @throws IllegalArgumentException if start > end
     */
    public BigDecimal getServiceChargeByScheme(long schemeId, LocalDate start, LocalDate end) {
        validateRange(start, end);
        return coalesce(store.sumServiceChargeBySchemeAndDateRange(schemeId, start, end));
    }

    /**
     * Net rounding gain/loss (USD) posted to {@code REVENUE_ROUNDING} over the date range. Positive =
     * net rounding gain, negative = net loss; reconciles to the sum of the period's posted residuals
     * (T27). Rounding journals key off transaction reference, not partner, so this is a service-wide
     * figure for the period rather than a per-partner one.
     *
     * @throws IllegalArgumentException if start > end
     */
    public BigDecimal getRoundingTotalUsd(LocalDate start, LocalDate end) {
        validateRange(start, end);
        return coalesce(journalStore.sumRoundingByDateRange(start, end, REPORTING_CCY));
    }

    private static void validateRange(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "start required");
        Objects.requireNonNull(end, "end required");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start must be <= end, got start=" + start + " end=" + end);
        }
    }

    private static BigDecimal coalesce(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
