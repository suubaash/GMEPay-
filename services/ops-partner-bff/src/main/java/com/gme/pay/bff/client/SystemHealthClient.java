package com.gme.pay.bff.client;

import java.time.Instant;
import java.util.List;

/**
 * Read-only aggregator over per-service health probes. Production
 * implementation fans out to each backend service's {@code /actuator/health}
 * endpoint; Phase-1 default is an in-memory stub returning all 17 backend
 * services as {@code UP} so the Admin UI System Health page can render
 * without an operating service mesh.
 *
 * <p>The 17 backend services covered match {@code docs/SERVICE_MAP.md}:
 * api-gateway, auth-identity, config-registry, rate-fx, smart-router,
 * qr-service, prefunding, payment-executor, transaction-mgmt,
 * scheme-adapter-zeropay, merchant-qr-data, notification-webhook,
 * settlement-reconciliation, revenue-ledger, reporting-compliance,
 * ops-partner-bff, and security-platform.
 */
public interface SystemHealthClient {

    /**
     * Returns a snapshot of the full system's health at this instant.
     */
    SystemHealth check();

    /**
     * Top-level shape consumed by the Admin UI System Health page.
     *
     * @param checkedAt when this snapshot was taken
     * @param services per-service rows; never null, may be empty
     */
    record SystemHealth(
            Instant checkedAt,
            List<ServiceHealth> services
    ) {}

    /**
     * Per-service health row.
     *
     * @param name short service id (e.g. {@code "rate-fx"})
     * @param status one of {@code "UP"}, {@code "DOWN"}, {@code "DEGRADED"},
     *               {@code "UNKNOWN"}
     * @param lastSeenAt the most recent successful probe time
     * @param uptimeSec uptime in seconds since the service last restarted;
     *                  null when unknown
     */
    record ServiceHealth(
            String name,
            String status,
            Instant lastSeenAt,
            Long uptimeSec
    ) {}
}
