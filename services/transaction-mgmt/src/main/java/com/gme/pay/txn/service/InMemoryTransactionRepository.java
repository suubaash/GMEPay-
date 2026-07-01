package com.gme.pay.txn.service;

import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.persistence.TransactionEntity;
import com.gme.pay.txn.persistence.TransactionEntityMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
                                           String txnRef, String schemeTxnRef, String merchantId,
                                           Pageable pageable) {
        var fromInstant = from != null ? from.atStartOfDay().toInstant(ZoneOffset.UTC) : null;
        // 'to' is inclusive: advance to start of next day for < comparison
        var toInstant = to != null ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) : null;
        String statusStr = status != null ? status.name() : null;
        return jpaRepository
                .findByFilters(fromInstant, toInstant, statusStr, partnerId,
                        blankToNull(txnRef), blankToNull(schemeTxnRef), blankToNull(merchantId),
                        pageable)
                .map(TransactionEntityMapper::toDomain);
    }

    /** Treat an empty / blank optional filter param as "no filter". */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    @Override
    public List<Transaction> findStuck(Instant stuckBefore, List<String> sweepStatuses) {
        return jpaRepository.findStuck(stuckBefore, sweepStatuses)
                .stream()
                .map(TransactionEntityMapper::toDomain)
                .collect(Collectors.toList());
    }

    /**
     * Sweepable (in-flight) statuses that can legally transition to FAILED on approval timeout.
     * APPROVED/FAILED/CANCELLED/REVERSED/REFUNDED are terminal and must never be swept.
     *
     * <p>UNCERTAIN is deliberately excluded: a scheme timeout holds the prefunding deduction and
     * the transaction is resolved only by batch reconciliation (resolveUncertain), never by the
     * approval-timeout sweeper. SCHEME_SENT IS included — a dispatch that never gets a response
     * and is not reclassified as UNCERTAIN must still fail out rather than linger forever.
     */
    private static final List<String> SWEEPABLE_STATUSES = List.of(
            TransactionStatus.CREATED.name(),
            TransactionStatus.PENDING_DEBIT.name(),
            TransactionStatus.SCHEME_SENT.name()
    );

    @Override
    public List<Transaction> findExpiredNonTerminal(Instant expiryBefore) {
        return jpaRepository.findExpiredNonTerminal(expiryBefore, SWEEPABLE_STATUSES)
                .stream()
                .map(TransactionEntityMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Transaction> findCommittedFx(LocalDate from, LocalDate to, Long partnerId) {
        // Default to a wide-open window when a bound is omitted: epoch start .. far future.
        var fromInstant = from != null
                ? from.atStartOfDay().toInstant(ZoneOffset.UTC)
                : Instant.EPOCH;
        // 'to' is inclusive on the date: advance to start of the next day for the < bound.
        var toInstant = to != null
                ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                : LocalDate.of(9999, 12, 31).atStartOfDay().toInstant(ZoneOffset.UTC);
        return jpaRepository.findCommittedFx(fromInstant, toInstant, partnerId)
                .stream()
                .map(TransactionEntityMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Transaction> findRefundedOn(LocalDate refundedOn) {
        var fromInstant = refundedOn.atStartOfDay().toInstant(ZoneOffset.UTC);
        var toInstant = refundedOn.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return jpaRepository.findRefundedOn(fromInstant, toInstant)
                .stream()
                .map(TransactionEntityMapper::toDomain)
                .collect(Collectors.toList());
    }
}
