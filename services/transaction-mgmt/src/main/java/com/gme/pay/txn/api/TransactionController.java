package com.gme.pay.txn.api;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.txn.api.dto.CreateTransactionRequest;
import com.gme.pay.txn.api.dto.TransactionResponse;
import com.gme.pay.txn.api.dto.TransitionRequest;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransitionBlockedException;
import com.gme.pay.txn.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
 */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
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
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@RequestBody CreateTransactionRequest req) {
        Transaction txn = transactionService.create(
                req.partnerRef(),
                req.sendAmount(),
                req.sendCcy(),
                req.targetPayout(),
                req.targetCcy());
        return TransactionResponse.from(txn);
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
