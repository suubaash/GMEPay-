package com.gme.pay.txn.service;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Application-layer service for transaction lifecycle operations.
 *
 * <p>All state mutations go through {@link TransactionStateMachine}; no caller
 * should set {@code status} directly.
 *
 * <p>Mutating methods are {@link Transactional} so the aggregate write and the outbox row
 * appended by the state machine (transactional Outbox pattern) commit atomically.
 *
 * <p>V003 adds:
 * <ul>
 *   <li>{@link #createFromPaymentExecutor} — 11-field create matching payment-executor contract</li>
 *   <li>{@link #patchStatus}               — PATCH /v1/transactions/{ref}/status</li>
 *   <li>{@link #queryTransactions}         — GET /v1/transactions (paged)</li>
 * </ul>
 */
@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final TransactionStateMachine stateMachine;

    public TransactionService(TransactionRepository repository,
                              TransactionStateMachine stateMachine) {
        this.repository = Objects.requireNonNull(repository);
        this.stateMachine = Objects.requireNonNull(stateMachine);
    }

    /**
     * Creates a new transaction in {@link TransactionStatus#CREATED} state
     * using the legacy 5-field signature. Preserved for backward compat.
     *
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} if amounts are invalid
     */
    @Transactional
    public Transaction create(String partnerRef,
                              BigDecimal sendAmount,
                              String sendCcy,
                              BigDecimal targetPayout,
                              String targetCcy) {
        if (sendAmount == null || sendAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "sendAmount must be > 0");
        }
        if (targetPayout == null || targetPayout.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "targetPayout must be > 0");
        }
        Transaction txn = new Transaction(partnerRef, sendAmount, sendCcy, targetPayout, targetCcy);
        return repository.save(txn);
    }

    /**
     * Creates a new transaction using the payment-executor 11-field contract.
     * This is the canonical path for POST /v1/transactions.
     *
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} if required amounts are invalid
     */
    @Transactional
    public Transaction createFromPaymentExecutor(Long partnerId,
                                                  String partnerTxnRef,
                                                  String schemeId,
                                                  String direction,
                                                  String paymentMode,
                                                  BigDecimal targetPayout,
                                                  String payoutCurrency,
                                                  BigDecimal collectionAmount,
                                                  String collectionCurrency,
                                                  String merchantId,
                                                  String quoteId) {
        if (collectionAmount == null || collectionAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "collectionAmount must be > 0");
        }
        if (targetPayout == null || targetPayout.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "targetPayout must be > 0");
        }
        Transaction txn = new Transaction(
                partnerId, partnerTxnRef, schemeId, direction, paymentMode,
                targetPayout, payoutCurrency, collectionAmount, collectionCurrency,
                merchantId, quoteId);
        return repository.save(txn);
    }

    /**
     * Retrieves a transaction by its service-internal reference.
     *
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} if not found
     */
    public Transaction getByTxnRef(String txnRef) {
        return repository.findByTxnRef(txnRef)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Transaction not found: " + txnRef));
    }

    /**
     * Applies the PATCH /v1/transactions/{ref}/status payload.
     * Maps {@code newStatus} to {@link TransactionStatus} and drives the state machine,
     * then persists the lock fields.
     *
     * @throws ApiException if the txnRef is not found or the status transition is unknown
     */
    @Transactional
    public Transaction patchStatus(String txnRef,
                                   String newStatus,
                                   String schemeTxnRef,
                                   String schemeApprovalCode,
                                   BigDecimal prefundDeductedUsd,
                                   Instant approvedAt,
                                   BigDecimal bookedSettlementAmount,
                                   String settlementRoundingMode,
                                   BigDecimal roundingResidual) {
        Transaction txn = getByTxnRef(txnRef);

        // Map from PaymentStatus (payment-executor) → TransactionStatus (this service).
        TransactionStatus target = mapPaymentStatus(newStatus);
        if (target != null) {
            stateMachine.transition(txn, target);
        }

        txn.applyStatusPatch(schemeTxnRef, schemeApprovalCode, prefundDeductedUsd, approvedAt,
                bookedSettlementAmount, settlementRoundingMode, roundingResidual);
        return repository.save(txn);
    }

    /**
     * Maps a PaymentStatus name (from payment-executor) to TransactionStatus.
     * Returns {@code null} for statuses that have no equivalent (PENDING, UNCERTAIN, etc.)
     * so the lock fields can still be applied without a state transition.
     */
    private TransactionStatus mapPaymentStatus(String paymentStatus) {
        if (paymentStatus == null) return null;
        return switch (paymentStatus) {
            case "APPROVED"  -> TransactionStatus.APPROVED;
            case "FAILED"    -> TransactionStatus.FAILED;
            case "CANCELLED" -> TransactionStatus.CANCELLED;
            case "PENDING"   -> TransactionStatus.PENDING_DEBIT;
            default          -> null; // UNCERTAIN, REVERSED, REFUNDED — no direct mapping yet
        };
    }

    /**
     * Paged query for GET /v1/transactions.
     *
     * @param from      filter: start date (inclusive, KST-aligned as UTC day boundary). Nullable.
     * @param to        filter: end date (inclusive). Nullable.
     * @param status    filter by status. Nullable.
     * @param partnerId filter by partner. Nullable.
     * @param page      zero-based page index
     * @param size      page size (max 500)
     */
    public Page<Transaction> queryTransactions(LocalDate from, LocalDate to,
                                               TransactionStatus status, Long partnerId,
                                               int page, int size) {
        int safeSize = Math.min(size, 500);
        PageRequest pageRequest = PageRequest.of(page, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.findByFilters(from, to, status, partnerId, pageRequest);
    }

    /**
     * Transitions a transaction to {@link TransactionStatus#PENDING_DEBIT}.
     * Typical caller: OVERSEAS commit path.
     */
    @Transactional
    public Transaction toPendingDebit(String txnRef) {
        Transaction txn = getByTxnRef(txnRef);
        stateMachine.transition(txn, TransactionStatus.PENDING_DEBIT);
        return repository.save(txn);
    }

    /**
     * Transitions a transaction to {@link TransactionStatus#APPROVED}.
     * Typical callers: scheme success response, LOCAL direct-commit, reconciliation.
     */
    @Transactional
    public Transaction toApproved(String txnRef) {
        Transaction txn = getByTxnRef(txnRef);
        stateMachine.transition(txn, TransactionStatus.APPROVED);
        return repository.save(txn);
    }

    /**
     * Transitions a transaction to {@link TransactionStatus#FAILED}.
     * Typical callers: scheme rejection, TTL expiry, insufficient prefunding.
     */
    @Transactional
    public Transaction toFailed(String txnRef) {
        Transaction txn = getByTxnRef(txnRef);
        stateMachine.transition(txn, TransactionStatus.FAILED);
        return repository.save(txn);
    }

    /**
     * Transitions a transaction to {@link TransactionStatus#CANCELLED}.
     * Typical caller: same-day cancel before or after debit.
     */
    @Transactional
    public Transaction toCancelled(String txnRef) {
        Transaction txn = getByTxnRef(txnRef);
        stateMachine.transition(txn, TransactionStatus.CANCELLED);
        return repository.save(txn);
    }
}
