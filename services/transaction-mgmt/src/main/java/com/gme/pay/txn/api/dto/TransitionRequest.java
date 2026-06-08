package com.gme.pay.txn.api.dto;

import com.gme.pay.txn.domain.model.TransactionStatus;

/**
 * Request body for state-transition endpoints
 * (POST /v1/transactions/{txnRef}/transitions).
 */
public record TransitionRequest(TransactionStatus targetStatus) {}
