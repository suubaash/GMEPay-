package com.gme.pay.txn.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.txn.api.dto.CreateTransactionRequest;
import com.gme.pay.txn.api.dto.CreateTransactionResponse;
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<Transaction> result = transactionService.queryTransactions(from, to, status, partnerId, page, size);
        List<TransactionResponse> content = result.getContent().stream()
                .map(TransactionResponse::from)
                .toList();
        TransactionQueryPageResponse response = TransactionQueryPageResponse.of(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements());
        return ResponseEntity.ok(response);
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
                req.quoteId());
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
                req.roundingResidual());
        return ResponseEntity.noContent().build();
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
