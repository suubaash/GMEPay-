package com.gme.pay.ledger.revenue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Phase-1 in-memory {@link RevenueRecordStore}. Retained as a NON-primary fallback bean — the
 * JPA-backed {@link com.gme.pay.ledger.persistence.JpaRevenueRecordStore} is {@code @Primary} and
 * wins in the Spring context. This impl stays for unit slices that want a context-free store.
 *
 * <p>Idempotency by txnRef is preserved (puts only if absent).
 */
@Component
public class InMemoryRevenueRecordStore implements RevenueRecordStore {

    private final ConcurrentMap<String, RevenueRecord> byTxnRef = new ConcurrentHashMap<>();

    @Override
    public RevenueRecord save(RevenueRecord record) {
        RevenueRecord existing = byTxnRef.putIfAbsent(record.txnRef(), record);
        return existing != null ? existing : record;
    }

    @Override
    public Optional<RevenueRecord> findByTxnRef(String txnRef) {
        return Optional.ofNullable(byTxnRef.get(txnRef));
    }

    @Override
    public BigDecimal sumFxMarginUsdByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end) {
        return byTxnRef.values().stream()
                .filter(r -> r.partnerId() == partnerId)
                .filter(r -> inRange(r, start, end))
                .map(RevenueRecord::fxMarginUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public BigDecimal sumServiceChargeByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end) {
        return byTxnRef.values().stream()
                .filter(r -> r.partnerId() == partnerId)
                .filter(r -> inRange(r, start, end))
                .map(RevenueRecord::serviceChargeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public long countByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end) {
        return byTxnRef.values().stream()
                .filter(r -> r.partnerId() == partnerId)
                .filter(r -> inRange(r, start, end))
                .count();
    }

    @Override
    public String serviceChargeCcyByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end) {
        return byTxnRef.values().stream()
                .filter(r -> r.partnerId() == partnerId)
                .filter(r -> inRange(r, start, end))
                .map(RevenueRecord::serviceChargeCcy)
                .findFirst()
                .orElse(null);
    }

    @Override
    public BigDecimal sumServiceChargeBySchemeAndDateRange(long schemeId, LocalDate start, LocalDate end) {
        return byTxnRef.values().stream()
                .filter(r -> r.schemeId() == schemeId)
                .filter(r -> inRange(r, start, end))
                .map(RevenueRecord::serviceChargeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public List<RevenueRecord> findByDateRangePaged(LocalDate start, LocalDate end, int page, int size) {
        return byTxnRef.values().stream()
                .filter(r -> inRange(r, start, end))
                .sorted((a, b) -> a.revenueDate().compareTo(b.revenueDate()))
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    private static boolean inRange(RevenueRecord r, LocalDate start, LocalDate end) {
        return !r.revenueDate().isBefore(start) && !r.revenueDate().isAfter(end);
    }
}
