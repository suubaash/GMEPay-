package com.gme.pay.txn.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.txn.api.dto.CreateTransactionRequest;
import com.gme.pay.txn.api.dto.TransactionResponse;
import com.gme.pay.txn.api.dto.TransitionRequest;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransitionBlockedException;
import com.gme.pay.txn.idempotency.IdempotencyStore;
import com.gme.pay.txn.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

/**
 * REST controller exposing the transaction lifecycle API.
 *
 * <p>Endpoints (wave scope):
 * <ul>
 *   <li>GET  /v1/transactions/{txnRef}                – retrieve a transaction</li>
 *   <li>POST /v1/transactions                         – create a transaction (CREATED)</li>
 *   <li>POST /v1/transactions/{txnRef}/transitions    – drive a state transition</li>
 * </ul>
 *
 * <p><b>Idempotency (17.3-G02).</b> POST /v1/transactions honours an optional
 * {@code Idempotency-Key} header: the first request to win the key stores a snapshot of its
 * response (Redis SETNX with 24h TTL in production; in-memory fallback locally) and returns
 * {@code 201 Created}; duplicates within the TTL — including concurrent ones — are answered
 * {@code 200 OK} with the identical stored body.
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
    // GET /v1/transactions/{txnRef}
    // -------------------------------------------------------------------------

    @GetMapping("/{txnRef}")
    public ResponseEntity<TransactionResponse> getById(@PathVariable String txnRef) {
        Transaction txn = transactionService.getByTxnRef(txnRef);
        return ResponseEntity.ok(TransactionResponse.from(txn));
    }

    // -------------------------------------------------------------------------
    // POST /v1/transactions  (create)
    // -------------------------------------------------------------------------

    @PostMapping
    public ResponseEntity<TransactionResponse> create(
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

        TransactionResponse fresh = doCreate(req);
        Optional<String> winner = idempotencyStore.putIfAbsent(idempotencyKey, writeSnapshot(fresh));
        if (winner.isPresent()) {
            // Lost a concurrent race — discard our body, replay the first stored snapshot
            // so every duplicate sees the identical response.
            log.info("idempotent replay for key={} (lost concurrent create race)", idempotencyKey);
            return ResponseEntity.ok(readSnapshot(winner.get()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(fresh);
    }

    private TransactionResponse doCreate(CreateTransactionRequest req) {
        Transaction txn = transactionService.create(
                req.partnerRef(),
                req.sendAmount(),
                req.sendCcy(),
                req.targetPayout(),
                req.targetCcy());
        return TransactionResponse.from(txn);
    }

    private String writeSnapshot(TransactionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotency snapshot", e);
        }
    }

    private TransactionResponse readSnapshot(String snapshot) {
        try {
            return objectMapper.readValue(snapshot, TransactionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize idempotency snapshot", e);
        }
    }

    // -------------------------------------------------------------------------
    // POST /v1/transactions/{txnRef}/transitions  (state machine)
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
