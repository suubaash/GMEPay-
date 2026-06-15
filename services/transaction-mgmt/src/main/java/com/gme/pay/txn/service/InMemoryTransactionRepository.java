package com.gme.pay.txn.service;

import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.persistence.TransactionEntity;
import com.gme.pay.txn.persistence.TransactionEntityMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Persistence adapter for {@link TransactionRepository}.
 *
 * <p>Originally a {@code ConcurrentHashMap}-backed Phase-1 stub; now wraps the
 * Spring Data JPA repository {@link com.gme.pay.txn.persistence.TransactionRepository}
 * and the {@link TransactionEntityMapper} so the service layer keeps using the
 * domain-layer port (this interface) without any caller change.
 *
 * <p>Class name is preserved to avoid renaming existing references; the underlying
 * storage is no longer in-memory.
 *
 * <p>V003 adds paged query delegation to Spring Data JPA.
 */
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final com.gme.pay.txn.persistence.TransactionRepository jpaRepository;

    public InMemoryTransactionRepository(
            com.gme.pay.txn.persistence.TransactionRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Transaction save(Transaction txn) {
        TransactionEntity entity = TransactionEntityMapper.toEntity(txn);
        TransactionEntity saved = jpaRepository.save(entity);
        return TransactionEntityMapper.toDomain(saved);
    }

    @Override
    public Optional<Transaction> findByTxnRef(String txnRef) {
        return jpaRepository.findById(txnRef).map(TransactionEntityMapper::toDomain);
    }

    @Override
    public Page<Transaction> findByFilters(LocalDate from, LocalDate to,
                                           TransactionStatus status, Long partnerId,
                                           Pageable pageable) {
        var fromInstant = from != null ? from.atStartOfDay().toInstant(ZoneOffset.UTC) : null;
        // 'to' is inclusive: advance to start of next day for < comparison
        var toInstant = to != null ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) : null;
        String statusStr = status != null ? status.name() : null;
        return jpaRepository
                .findByFilters(fromInstant, toInstant, statusStr, partnerId, pageable)
                .map(TransactionEntityMapper::toDomain);
    }
}
