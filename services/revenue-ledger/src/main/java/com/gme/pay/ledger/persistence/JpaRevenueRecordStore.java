package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.revenue.RevenueRecord;
import com.gme.pay.ledger.revenue.RevenueRecordStore;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JPA-backed {@link RevenueRecordStore} — the {@code @Primary} implementation in production. Backs
 * {@code GET /v1/revenue} with real per-partner aggregates (previously the in-memory store always
 * returned zeros). The companion {@link com.gme.pay.ledger.revenue.InMemoryRevenueRecordStore} is
 * kept as a non-primary fallback for context-free unit slices.
 *
 * <p>Idempotency by {@code txnRef}: a duplicate save returns the previously persisted record
 * unchanged (no overwrite, no duplicate row).
 */
@Component
@Primary
public class JpaRevenueRecordStore implements RevenueRecordStore {

    private final RevenueRecordJpaRepository repository;

    public JpaRevenueRecordStore(RevenueRecordJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository required");
    }

    @Override
    @Transactional
    public RevenueRecord save(RevenueRecord record) {
        Objects.requireNonNull(record, "record required");
        Optional<RevenueRecordEntity> existing = repository.findByTxnRef(record.txnRef());
        if (existing.isPresent()) {
            return existing.get().toDomain();
        }
        // MICROS truncation keeps the stored TIMESTAMP equal to the in-memory value on both
        // PostgreSQL and H2 (project-wide discipline).
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        RevenueRecordEntity saved = repository.save(RevenueRecordEntity.fromDomain(record, now));
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RevenueRecord> findByTxnRef(String txnRef) {
        return repository.findByTxnRef(txnRef).map(RevenueRecordEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal sumFxMarginUsdByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end) {
        return coalesce(repository.sumFxMarginUsdByPartner(partnerId, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal sumServiceChargeByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end) {
        return coalesce(repository.sumServiceChargeByPartner(partnerId, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public long countByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end) {
        return repository.countByPartnerIdAndRevenueDateBetween(partnerId, start, end);
    }

    @Override
    @Transactional(readOnly = true)
    public String serviceChargeCcyByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end) {
        return repository
                .findFirstByPartnerIdAndRevenueDateBetweenOrderByRevenueDateDesc(partnerId, start, end)
                .map(RevenueRecordEntity::getServiceChargeCcy)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal sumServiceChargeBySchemeAndDateRange(long schemeId, LocalDate start, LocalDate end) {
        return coalesce(repository.sumServiceChargeByScheme(schemeId, start, end));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevenueRecord> findByDateRangePaged(LocalDate start, LocalDate end, int page, int size) {
        return repository
                .findByRevenueDateBetweenOrderByRevenueDateAsc(start, end, PageRequest.of(page, size))
                .stream()
                .map(RevenueRecordEntity::toDomain)
                .toList();
    }

    private static BigDecimal coalesce(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
