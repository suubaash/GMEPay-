package com.gme.pay.scheme.ninepay.dto;

/**
 * Response for POST /scheme/payout and GET /scheme/payout/{requestId}.
 *
 * @param requestId         the idempotency key echoed back
 * @param transactionId     9Pay transaction code; null until 9Pay has accepted the transfer
 * @param status            canonical {@code PayoutStatus} name: SUBMITTED / PENDING / PROCESSING /
 *                          SUCCESS / FAILED / HELD / REVERSED / UNKNOWN. UNKNOWN = ambiguous
 *                          timeout unresolved by polling — do NOT treat as failed; re-query later.
 * @param amountVnd         requested amount, integer VND
 * @param feeVnd            9Pay fee when known (null otherwise)
 * @param transferAmountVnd amount 9Pay debits from the prefunded balance when known
 *                          (fee-inclusion semantics unconfirmed — open issue O6)
 * @param schemeMessage     bank/9Pay message when present
 * @param errorCode         9Pay {@code error.code} when the submission was definitively
 *                          rejected (status FAILED); null otherwise
 */
public record PayoutResponse(
        String requestId,
        String transactionId,
        String status,
        long amountVnd,
        Long feeVnd,
        Long transferAmountVnd,
        String schemeMessage,
        String errorCode
) {}
