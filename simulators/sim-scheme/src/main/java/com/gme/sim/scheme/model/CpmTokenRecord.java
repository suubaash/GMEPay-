package com.gme.sim.scheme.model;

import java.time.Instant;

/**
 * In-memory CPM token record.
 */
public record CpmTokenRecord(
        String token,
        String customerId,
        String fundingRef,
        Instant expiresAt
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
