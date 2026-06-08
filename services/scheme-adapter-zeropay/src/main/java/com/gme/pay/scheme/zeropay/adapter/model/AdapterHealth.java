package com.gme.pay.scheme.zeropay.adapter.model;

import java.time.Instant;

/**
 * Snapshot of a scheme adapter's operational health, returned by
 * {@link com.gme.pay.scheme.zeropay.adapter.SchemeAdapter#healthCheck()}.
 *
 * <p>Never throws — all errors are captured in the fields here.</p>
 */
public record AdapterHealth(
        HealthStatus status,
        Instant lastCheckedAt,
        boolean sftpReachable,
        boolean realtimeApiReachable,
        String lastError
) {

    public static AdapterHealth up() {
        return new AdapterHealth(HealthStatus.UP, Instant.now(), true, true, null);
    }

    public static AdapterHealth down(String reason) {
        return new AdapterHealth(HealthStatus.DOWN, Instant.now(), false, false, reason);
    }

    public static AdapterHealth degraded(boolean sftpOk, boolean apiOk, String reason) {
        return new AdapterHealth(HealthStatus.DEGRADED, Instant.now(), sftpOk, apiOk, reason);
    }
}
