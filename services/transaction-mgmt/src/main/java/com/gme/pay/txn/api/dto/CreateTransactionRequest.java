package com.gme.pay.txn.api.dto;

import java.math.BigDecimal;

/**
 * Request body for POST /v1/transactions (create a new transaction).
 */
public record CreateTransactionRequest(
        String partnerRef,
        BigDecimal sendAmount,
        String sendCcy,
        BigDecimal targetPayout,
        String targetCcy
) {}
