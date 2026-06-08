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
        // Transaction count and per-scheme breakdown are deferred to the full persistence layer;
        // this wave returns a single aggregate per partner with zeroed scheme/count fields.
        return new RevenueAggregate(partnerId, 0L, 0L,
                coalesce(fxMargin), BigDecimal.ZERO, "USD");
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
