package com.gme.pay.txn.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.contracts.CommittedFxView;
import com.gme.pay.contracts.RefundedTransactionView;
import com.gme.pay.txn.api.dto.CreateTransactionRequest;
import com.gme.pay.txn.api.dto.CreateTransactionResponse;
import com.gme.pay.txn.api.dto.ResolveTransactionRequest;
import com.gme.pay.txn.api.dto.StatusPatchRequest;
import com.gme.pay.txn.api.dto.TransactionQueryPageResponse;
import com.gme.pay.txn.api.dto.TransactionResponse;
import com.gme.pay.txn.api.dto.TransitionRequest;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransitionBlockedException;
import com.gme.pay.txn.idempotency.IdempotencyStore;
import com.gme.pay.txn.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller exposing the transaction lifecycle API.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET    /v1/transactions                          – paged list by date/status/partner</li>
 *   <li>GET    /v1/transactions/{txnRef}                 – retrieve a single transaction</li>
 *   <li>POST   /v1/transactions                          – create a transaction (payment-executor 11-field contract)</li>
 *   <li>PATCH  /v1/transactions/{ref}/status             – set status + lock fields (payment-executor 8-field contract)</li>
 *   <li>POST   /v1/transactions/{txnRef}/transitions     – drive a state transition (legacy)</li>
 * </ul>
 *
 * <p><b>Idempotency (17.3-G02).</b> POST /v1/transactions honours an optional
 * {@code Idempotency-Key} header.
 */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    public TransactionController(TransactionService transactionService,
                                 IdempotencyStore idempotencyStore,
                                 ObjectMapper objectMapper) {
        this.transactionService = transactionService;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // GET /v1/transactions  (paged query)
    // -------------------------------------------------------------------------

    /**
     * Returns a page of transactions matching the given filters.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code from}      – start date inclusive (ISO date, e.g. 2026-01-01). Optional.</li>
     *   <li>{@code to}        – end date inclusive (ISO date). Optional.</li>
     *   <li>{@code status}    – filter by TransactionStatus name. Optional.</li>
     *   <li>{@code partnerId} – filter by numeric partner ID. Optional.</li>
     *   <li>{@code page}      – zero-based page index (default 0).</li>
     *   <li>{@code size}      – page size (default 20, max 500).</li>
     * </ul>
     *
     * <p>Response: {@code { content:[TransactionResponse], page, size, totalElements }}
     */
    @GetMapping
    public ResponseEntity<TransactionQueryPageResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String txnRef,
            @RequestParam(required = false) String schemeTxnRef,
            @RequestParam(required = false) String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(runSearch(
                from, to, status, partnerId, txnRef, schemeTxnRef, merchantId, page, size));
    }

    // -------------------------------------------------------------------------
    // GET /v1/transactions/search  (360° operator drill-down search)
    // -------------------------------------------------------------------------

    /**
     * 360° transaction search for operator drill-down. Same flexible optional filters as the list
     * endpoint — {@code txnRef}, {@code partnerId}, {@code schemeTxnRef}, {@code status},
     * {@code merchantId}, {@code from}/{@code to} date — returning the paged transaction
     * projection. A literal path segment, so it never collides with {@code GET /{txnRef}}.
     */
    @GetMapping("/search")
    public ResponseEntity<TransactionQueryPageResponse> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String txnRef,
            @RequestParam(required = false) String schemeTxnRef,
            @RequestParam(required = false) String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(runSearch(
                from, to, status, partnerId, txnRef, schemeTxnRef, merchantId, page, size));
    }

    /** Shared paged-search implementation behind both {@code GET /} and {@code GET /search}. */
    private TransactionQueryPageResponse runSearch(
            LocalDate from, LocalDate to, TransactionStatus status, Long partnerId,
            String txnRef, String schemeTxnRef, String merchantId, int page, int size) {
        Page<Transaction> result = transactionService.queryTransactions(
                from, to, status, partnerId, txnRef, schemeTxnRef, merchantId, page, size);
        List<TransactionResponse> content = result.getContent().stream()
                .map(TransactionResponse::from)
                .toList();
        return TransactionQueryPageResponse.of(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements());
    }

    // -------------------------------------------------------------------------
    // GET /v1/transactions/{txnRef}
    // -------------------------------------------------------------------------

    @GetMapping("/{txnRef}")
    public ResponseEntity<TransactionResponse> getById(@PathVariable String txnRef) {
        Transaction txn = transactionService.getByTxnRef(txnRef);
        return ResponseEntity.ok(TransactionResponse.from(txn));
    }

    // -------------------------------------------------------------------------
    // GET /v1/transactions/fx-committed  (committed-FX projection — Phase 2)
    // -------------------------------------------------------------------------

    /**
     * Returns the committed cross-border transactions in the date window as the canonical
     * {@link CommittedFxView} projection — the rate-locked FX fields captured at commit
     * (offerRateColl = BOK FX1015 #14, crossRate, margins, USD amount). Consumed by
     * reporting-compliance (FX1015), settlement-reconciliation (netting) and scheme-adapter.
     *
     * <p>Query params: {@code from} / {@code to} (inclusive ISO dates, optional) and
     * {@code partnerId} (optional). A literal path segment, so it never collides with
     * {@code GET /{txnRef}}.
     */
    @GetMapping("/fx-committed")
    public ResponseEntity<List<CommittedFxView>> fxCommitted(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long partnerId) {
        return ResponseEntity.ok(transactionService.findCommittedFx(from, to, partnerId));
    }

    // -------------------------------------------------------------------------
    // GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD  (refund query — Phase 2)
    // -------------------------------------------------------------------------

    /**
     * Returns the transactions refunded on the given calendar day, as the canonical shared
     * {@link RefundedTransactionView} (lib-api-contracts). transaction-mgmt is the AUTHORITATIVE
     * producer; the canonical view mirrors this projection's field names verbatim, so
     * settlement-reconciliation (cross-date refund netting) and scheme-adapter-zeropay (refund
     * detail enrichment) bind one type and stop silently null-binding their divergent ad-hoc
     * records. {@code settlementDate} is sourced from the committed settlement-window data (the
     * value date the refund nets onto); null when no settlement window has been booked yet.
     */
    @GetMapping("/refunded")
    public ResponseEntity<List<RefundedTransactionView>> refunded(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate refundedOn) {
        List<RefundedTransactionView> body = transactionService.findRefundedOn(refundedOn).stream()
                .map(TransactionController::toRefundedView)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** Maps a refunded aggregate to the canonical {@link RefundedTransactionView}. */
    private static RefundedTransactionView toRefundedView(Transaction txn) {
        return new RefundedTransactionView(
                txn.txnRef(),
                txn.originalPaymentTxnRef(),
                txn.partnerId(),
                txn.status() != null ? txn.status().name() : null,
                txn.merchantId(),
                txn.qrCodeId(),
                txn.schemeTxnRef(),
                txn.refundAmountKrw(),
                txn.targetCcy(),
                txn.merchantFeeRate(),
                txn.refundedAt(),
                txn.approvedAt(),
                txn.settlementDate());
    }

    // -------------------------------------------------------------------------
    // POST /v1/transactions  (create — payment-executor 11-field contract)
    // -------------------------------------------------------------------------

    /**
     * Creates a transaction. Accepts payment-executor's 11-field {@code TransactionCreateRequest}.
     * Returns {@code { txnRef, paymentId, createdAt }}.
     */
    @PostMapping
    public ResponseEntity<CreateTransactionResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateTransactionRequest req) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(doCreate(req));
        }

        // Fast-path replay: a duplicate after the first response was stored.
        Optional<String> replayed = idempotencyStore.get(idempotencyKey);
        if (replayed.isPresent()) {
            return ResponseEntity.ok(readSnapshot(replayed.get()));
        }

        CreateTransactionResponse fresh = doCreate(req);
        Optional<String> winner = idempotencyStore.putIfAbsent(idempotencyKey, writeSnapshot(fresh));
        if (winner.isPresent()) {
            log.info("idempotent replay for key={} (lost concurrent create race)", idempotencyKey);
            return ResponseEntity.ok(readSnapshot(winner.get()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(fresh);
    }

    private CreateTransactionResponse doCreate(CreateTransactionRequest req) {
        Transaction txn = transactionService.createFromPaymentExecutor(
                req.partnerId(),
                req.partnerTxnRef(),
                req.schemeId(),
                req.direction(),
                req.paymentMode(),
                req.targetPayout(),
                req.payoutCurrency(),
                req.collectionAmount(),
                req.collectionCurrency(),
                req.merchantId(),
                req.quoteId(),
                req.merchantFeeRate(),
                req.collectionMarginUsd(),
                req.payoutMarginUsd(),
                req.collectionUsd(),
                req.costRateColl(),
                req.costRatePay(),
                req.payoutUsdCost());
        return CreateTransactionResponse.from(txn);
    }

    private String writeSnapshot(CreateTransactionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotency snapshot", e);
        }
    }

    private CreateTransactionResponse readSnapshot(String snapshot) {
        try {
            return objectMapper.readValue(snapshot, CreateTransactionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize idempotency snapshot", e);
        }
    }

    // -------------------------------------------------------------------------
    // PATCH /v1/transactions/{ref}/status  (payment-executor 8-field contract)
    // -------------------------------------------------------------------------

    /**
     * Applies a status transition and persists lock fields.
     * Accepts payment-executor's 8-field {@code StatusPatchRequest}.
     * Returns 204 No Content on success.
     */
    @PatchMapping("/{txnRef}/status")
    public ResponseEntity<Void> patchStatus(
            @PathVariable String txnRef,
            @RequestBody StatusPatchRequest req) {

        transactionService.patchStatus(
                txnRef,
                req.newStatus(),
                req.schemeTxnRef(),
                req.schemeApprovalCode(),
                req.prefundDeductedUsd(),
                req.approvedAt(),
                req.bookedSettlementAmount(),
                req.settlementRoundingMode(),
                req.roundingResidual(),
                req.collectionMarginUsd(),
                req.payoutMarginUsd(),
                req.collectionUsd(),
                req.costRateColl(),
                req.costRatePay());
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // POST /v1/transactions/{txnRef}/resolve  (Ops force-resolve UNCERTAIN)
    // -------------------------------------------------------------------------

    /**
     * Operator force-resolution of a stuck UNCERTAIN transaction. Body:
     * {@code { resolution: COMPLETED|REVERSED, reason, operator }}. Transitions the txn to the
     * chosen terminal state via the real FSM (COMPLETED→APPROVED, REVERSED→REVERSED) and records
     * the reason + operator in the transaction audit. Idempotent — a repeat once resolved returns
     * the resolved state; rejects a txn that is not UNCERTAIN.
     */
    @PostMapping("/{txnRef}/resolve")
    public ResponseEntity<TransactionResponse> resolve(
            @PathVariable String txnRef,
            @RequestBody ResolveTransactionRequest req) {
        Transaction txn = transactionService.resolveByOperator(
                txnRef, req.resolution(), req.reason(), req.operator());
        return ResponseEntity.ok(TransactionResponse.from(txn));
    }

    // -------------------------------------------------------------------------
    // POST /v1/transactions/{txnRef}/transitions  (state machine — legacy)
    // -------------------------------------------------------------------------

    @PostMapping("/{txnRef}/transitions")
    public TransactionResponse transition(
            @PathVariable String txnRef,
            @RequestBody TransitionRequest req) {

        TransactionStatus target = req.targetStatus();
        Transaction txn = switch (target) {
            case PENDING_DEBIT -> transactionService.toPendingDebit(txnRef);
            case APPROVED      -> transactionService.toApproved(txnRef);
            case FAILED        -> transactionService.toFailed(txnRef);
            case CANCELLED     -> transactionService.toCancelled(txnRef);
            default -> throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "Cannot transition directly to " + target);
        };
        return TransactionResponse.from(txn);
    }

    // -------------------------------------------------------------------------
    // Exception handlers
    // -------------------------------------------------------------------------

    @ExceptionHandler(TransitionBlockedException.class)
    public ResponseEntity<ApiError> handleTransitionBlocked(TransitionBlockedException ex) {
        log.warn("Transition blocked: {}", ex.getMessage());
        ApiError body = ApiError.of(
                ErrorCode.VALIDATION_ERROR,
                ex.getMessage(),
                UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        ApiError body = ApiError.of(ex.errorCode(), ex.getMessage(), UUID.randomUUID().toString());
        return ResponseEntity.status(ex.errorCode().httpStatus()).body(body);
    }
}
