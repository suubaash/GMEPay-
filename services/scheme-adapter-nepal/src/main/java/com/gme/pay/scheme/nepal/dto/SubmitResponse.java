package com.gme.pay.scheme.nepal.dto;

/**
 * Response for POST /internal/scheme/nepal/submit.
 *
 * @param schemeTxnRef the partner {@code idx} (scheme transaction reference)
 * @param status       canonical state: APPROVED / PENDING / REJECTED
 * @param amountPaisa  amount echoed by the partner, in paisa
 */
public record SubmitResponse(
        String schemeTxnRef,
        String status,
        long amountPaisa
) {}
