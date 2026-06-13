package com.gme.sim.scheme.dto;

import java.math.BigDecimal;

public record PaymentStateResponse(
        String authId,
        String status,
        String merchantId,
        BigDecimal amount,
        String currency,
        String payerRef,
        String schemeRef,
        String authorizedAt,   // KST ISO-8601
        String schemeTxnRef,   // null if not captured
        String committedAt,    // null if not captured
        String refundId,       // null if not refunded
        String refundedAt      // null if not refunded
) {}
