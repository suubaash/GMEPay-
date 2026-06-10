package com.gme.pay.ledger.revenue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Phase-1 in-memory {@link RevenueRecordStore}. Provided so the Spring context can resolve the
 * port dependency (RevenueRecordService autowires it). A JPA-backed implementation will replace
 * this in a future PR (alongside a revenue_records table + Flyway migration), at which point
 * this fallback can be deleted or kept as a test profile.
 *
 * <p>Idempotency by txnId is preserved (puts only if absent).
 */
@Component
public class InMemoryRevenueRecordStore implements RevenueRecordStore {

    private final ConcurrentMap<Long, RevenueRecord> byTxnId = new ConcurrentHashMap<>();

    @Override
    public RevenueRecord save(RevenueRecord record) {
        RevenueRecord existing = byTxnId.putIfAbsent(record.txnId(), record);
        return existing != null ? existing : record;
    }

    @Override
    public Optional<RevenueRecord> findByTxnId(long txnId) {
        return Optional.ofNullable(byTxnId.get(txnId));
    }

    @Override
    public BigDecimal sumFxMarginUsdByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end) {
        return byTxnId.values().stream()
                .filter(r -> r.partnerId() == partnerId)
                .filter(r -> !r.revenueDate().isBefore(start) && !r.revenueDate().isAfter(end))
                .map(RevenueRecord::fxMarginUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public BigDecimal sumServiceChargeBySchemeAndDateRange(long schemeId, LocalDate start, LocalDate end) {
        return byTxnId.values().stream()
                .filter(r -> r.schemeId() == schemeId)
                .filter(r -> !r.revenueDate().isBefore(start) && !r.revenueDate().isAfter(end))
                .map(RevenueRecord::serviceChargeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public List<RevenueRecord> findByDateRangePaged(LocalDate start, LocalDate end, int page, int size) {
        return byTxnId.values().stream()
                .filter(r -> !r.revenueDate().isBefore(start) && !r.revenueDate().isAfter(end))
                .sorted((a, b) -> a.revenueDate().compareTo(b.revenueDate()))
                .skip((long) page * size)
                .limit(size)
                .toList();
    }
}
