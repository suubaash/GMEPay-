package com.gme.pay.ledger.revenue;

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

    private final RevenueRecordStore store;

    public RevenueRecordService(RevenueRecordStore store) {
        this.store = Objects.requireNonNull(store);
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
