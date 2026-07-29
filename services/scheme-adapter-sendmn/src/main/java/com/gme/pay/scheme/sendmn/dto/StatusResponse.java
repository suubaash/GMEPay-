package com.gme.pay.scheme.sendmn.dto;

/**
 * Response for GET /internal/scheme/sendmn/status/{txTokenNo}.
 *
 * @param txTokenNo        echoed idempotency key
 * @param status           canonical state: APPROVED / PENDING / UNKNOWN
 * @param paymentNo        SendMN API tracking number (nullable)
 * @param paymentReceiptNo SendMN control number (nullable)
 */
public record StatusResponse(
        String txTokenNo,
        String status,
        String paymentNo,
        String paymentReceiptNo
) {}
