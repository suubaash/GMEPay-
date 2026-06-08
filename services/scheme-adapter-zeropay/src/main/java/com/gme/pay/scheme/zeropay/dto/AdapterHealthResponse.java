package com.gme.pay.scheme.zeropay.dto;

import java.time.Instant;

/**
 * Response DTO for {@code GET /internal/scheme/zeropay/health}.
 */
public record AdapterHealthResponse(
        String status,
        Instant lastCheckedAt,
        boolean sftpReachable,
        boolean realtimeApiReachable,
        String lastError
) {}
