package com.gme.pay.txn.service;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionStateMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Application-layer service for transaction lifecycle operations.
 *
 * <p>All state mutations go through {@link TransactionStateMachine}; no caller
 * should set {@code status} directly.
 *
 * <p>Mutating methods are {@link Transactional} so the aggregate write and the outbox row
 * appended by the state machine (transactional Outbox pattern) commit atomically.
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
     * Creates a new transaction in {@link TransactionStatus#CREATED} state.
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
     * Retrieves a transaction by its service-internal reference.
     *
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} if not found
     *         (a dedicated NOT_FOUND code would be added to lib-errors in the full build)
     */
    public Transaction getByTxnRef(String txnRef) {
        return repository.findByTxnRef(txnRef)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Transaction not found: " + txnRef));
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
