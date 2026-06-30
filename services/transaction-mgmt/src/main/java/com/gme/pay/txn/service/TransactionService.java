package com.gme.pay.txn.service;

import com.gme.pay.contracts.CommittedFxView;
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
import java.util.List;
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
                                                  String quoteId,
                                                  BigDecimal merchantFeeRate) {
        if (collectionAmount == null || collectionAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "collectionAmount must be > 0");
        }
        if (targetPayout == null || targetPayout.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "targetPayout must be > 0");
        }
        // V005: guard the merchant-fee snapshot before it hits NUMERIC(7,4) — reject
        // out-of-scale (would be silently rounded by the DB, mutating the "rate that
        // applied") or out-of-range (a fee rate is a fraction in [0,1]; >999.9999 would
        // overflow the column → 500). Mirrors config-registry's upstream guard.
        if (merchantFeeRate != null) {
            if (merchantFeeRate.stripTrailingZeros().scale() > 4) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "merchantFeeRate must have at most 4 decimal places, was: "
                                + merchantFeeRate.toPlainString());
            }
            if (merchantFeeRate.signum() < 0 || merchantFeeRate.compareTo(BigDecimal.ONE) > 0) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "merchantFeeRate must be between 0 and 1, was: "
                                + merchantFeeRate.toPlainString());
            }
        }
        Transaction txn = new Transaction(
                partnerId, partnerTxnRef, schemeId, direction, paymentMode,
                targetPayout, payoutCurrency, collectionAmount, collectionCurrency,
                merchantId, quoteId);
        // V005: snapshot the gross merchant fee rate the caller resolved at creation.
        txn.applyMerchantFeeRate(merchantFeeRate);
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
        // Skip the transition when the target equals the current status: a PATCH that merely
        // re-asserts the same status (idempotent retry) must still apply the lock fields, but
        // a self-edge is not legal in the FSM and would raise TransitionBlockedException.
        TransactionStatus target = mapPaymentStatus(newStatus);
        if (target != null && target != txn.status()) {
            stateMachine.transition(txn, target);
        }

        txn.applyStatusPatch(schemeTxnRef, schemeApprovalCode, prefundDeductedUsd, approvedAt,
                bookedSettlementAmount, settlementRoundingMode, roundingResidual);
        return repository.save(txn);
    }

    /**
     * Maps a PaymentStatus name (from payment-executor) to TransactionStatus.
     *
     * <p>Every payment-executor status now maps to a real {@link TransactionStatus} so the
     * PATCH endpoint performs an actual FSM transition (P1 gap: cancel/refund/uncertain were
     * previously applied as lock-field-only updates with no state change). Returns {@code null}
     * only for an unknown / unmapped status string, so the lock fields can still be applied
     * without an (illegal) transition.
     *
     * <p>{@code SCHEME_SENT} maps to itself; {@code UNCERTAIN} maps to {@link TransactionStatus#UNCERTAIN}
     * (scheme timeout — held pending reconciliation). When the current status already equals the
     * target the caller skips the transition (re-applying the same status is not a legal self-edge).
     */
    private TransactionStatus mapPaymentStatus(String paymentStatus) {
        if (paymentStatus == null) return null;
        return switch (paymentStatus) {
            case "APPROVED"    -> TransactionStatus.APPROVED;
            case "FAILED"      -> TransactionStatus.FAILED;
            case "CANCELLED"   -> TransactionStatus.CANCELLED;
            case "REVERSED"    -> TransactionStatus.REVERSED;
            case "REFUNDED"    -> TransactionStatus.REFUNDED;
            case "PENDING"     -> TransactionStatus.PENDING_DEBIT;
            case "SCHEME_SENT" -> TransactionStatus.SCHEME_SENT;
            case "UNCERTAIN"   -> TransactionStatus.UNCERTAIN;
            default            -> null; // unknown status — lock fields applied without a transition
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
     * Committed-FX projection feed (GET /v1/transactions/fx-committed). Maps each committed
     * transaction to the canonical {@link CommittedFxView} contract — the rate-locked FX fields
     * captured at commit. {@code from}/{@code to} are inclusive date bounds (nullable); a null
     * partnerId returns all partners.
     *
     * <p>{@code direction} rides as the wire String per the contract. Same-currency short-circuit
     * rows carry null offerRateColl/crossRate (no FX leg).
     */
    public List<CommittedFxView> findCommittedFx(LocalDate from, LocalDate to, Long partnerId) {
        return repository.findCommittedFx(from, to, partnerId).stream()
                .map(TransactionService::toCommittedFxView)
                .toList();
    }

    /** Maps a committed aggregate to the canonical {@link CommittedFxView}. */
    static CommittedFxView toCommittedFxView(Transaction txn) {
        return new CommittedFxView(
                stableTxnId(txn.txnRef()),
                txn.txnRef(),
                txn.partnerId() != null ? txn.partnerId() : 0L,
                txn.direction(),
                Boolean.TRUE.equals(txn.sameCcyShortcircuit()),
                txn.offerRateColl(),
                txn.crossRate(),
                txn.collectionAmount() != null ? txn.collectionAmount() : txn.sendAmount(),
                txn.collectionCurrency() != null ? txn.collectionCurrency() : txn.sendCcy(),
                txn.targetPayout(),
                txn.payoutCurrency() != null ? txn.payoutCurrency() : txn.targetCcy(),
                txn.usdAmount(),
                txn.collectionMarginUsd(),
                txn.payoutMarginUsd(),
                txn.committedAt());
    }

    /**
     * Derives a stable numeric txnId for the projection's {@code long txnId} slot. The aggregate's
     * key is a UUID string; consumers (reporting-compliance CommittedTransaction) carry a long id,
     * so we expose a deterministic non-negative hash of the txnRef. The authoritative key remains
     * {@code txnRef}; this is purely the contract's numeric handle.
     */
    static long stableTxnId(String txnRef) {
        return txnRef == null ? 0L : (txnRef.hashCode() & 0x7fffffffL);
    }

    /**
     * Refund query (GET /v1/transactions/refunded?refundedOn). Returns the transactions refunded
     * on the given calendar day, as domain aggregates (the controller maps to the refund DTO with
     * the original payment txnRef). Settlement-reconciliation uses this for cross-date refund
     * netting against the refund date rather than the original creation date.
     */
    public List<Transaction> findRefundedOn(LocalDate refundedOn) {
        if (refundedOn == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "refundedOn is required");
        }
        return repository.findRefundedOn(refundedOn);
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

    /**
     * Transitions a transaction to {@link TransactionStatus#SCHEME_SENT}.
     * Called when the scheme adapter dispatch is about to be issued; recorded
     * <em>before</em> the HTTP call so a crash mid-flight leaves a reconcilable row.
     */
    @Transactional
    public Transaction toSchemeSent(String txnRef) {
        Transaction txn = getByTxnRef(txnRef);
        stateMachine.transition(txn, TransactionStatus.SCHEME_SENT);
        return repository.save(txn);
    }

    /**
     * Transitions a transaction to {@link TransactionStatus#UNCERTAIN}.
     * Typical caller: scheme adapter timeout (no response within SLA). The prefunding
     * deduction is held — reversal happens only on a FAILED reconciliation outcome.
     */
    @Transactional
    public Transaction toUncertain(String txnRef) {
        Transaction txn = getByTxnRef(txnRef);
        stateMachine.transition(txn, TransactionStatus.UNCERTAIN);
        return repository.save(txn);
    }

    /**
     * Resolves an {@link TransactionStatus#UNCERTAIN} transaction via batch reconciliation
     * (ZP0012 / ZP0022 from ZeroPay, ~05:00 KST). Idempotent: if the transaction is no longer
     * UNCERTAIN (already resolved by a prior call), this is a no-op and returns the row unchanged.
     *
     * @param txnRef  the transaction reference
     * @param outcome the resolved terminal status — must be {@link TransactionStatus#APPROVED}
     *                or {@link TransactionStatus#FAILED}
     * @throws ApiException with {@link ErrorCode#VALIDATION_ERROR} if {@code outcome} is not
     *                      APPROVED or FAILED
     */
    @Transactional
    public Transaction resolveUncertain(String txnRef, TransactionStatus outcome) {
        if (outcome != TransactionStatus.APPROVED && outcome != TransactionStatus.FAILED) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "resolveUncertain outcome must be APPROVED or FAILED, was: " + outcome);
        }
        Transaction txn = getByTxnRef(txnRef);
        // Idempotency guard: only an UNCERTAIN transaction can be resolved. A second call
        // (already APPROVED/FAILED) is a no-op so the reconciliation job can re-run safely.
        if (txn.status() != TransactionStatus.UNCERTAIN) {
            return txn;
        }
        stateMachine.transition(txn, outcome);
        return repository.save(txn);
    }
}
