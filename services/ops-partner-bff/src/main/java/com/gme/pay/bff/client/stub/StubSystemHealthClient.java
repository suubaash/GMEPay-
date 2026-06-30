package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.SystemHealthClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase-1 in-memory stub of {@link SystemHealthClient}. Returns all 17 backend
 * services as {@code UP} with deterministic per-service uptimes so the Admin
 * UI System Health page renders without an operating service mesh.
 *
 * <p>Default bean: wired unless {@code gmepay.system-health.client=rest} selects
 * the live {@link com.gme.pay.bff.client.rest.RestSystemHealthClient} fan-out.
 */
@Component
@ConditionalOnProperty(
        name = "gmepay.system-health.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubSystemHealthClient implements SystemHealthClient {

    /** Service ids (matches {@code docs/SERVICE_MAP.md}). */
    private static final String[] SERVICES = {
            "api-gateway",
            "auth-identity",
            "config-registry",
            "rate-fx",
            "smart-router",
            "qr-service",
            "prefunding",
            "payment-executor",
            "transaction-mgmt",
            "scheme-adapter-zeropay",
            "merchant-qr-data",
            "notification-webhook",
            "settlement-reconciliation",
            "revenue-ledger",
            "reporting-compliance",
            "ops-partner-bff",
            "security-platform"
    };

    /** Base uptime in seconds; each service is offset deterministically by index. */
    private static final long BASE_UPTIME_SEC = 86_400L; // 1 day

    @Override
    public SystemHealth check() {
        Instant checkedAt = Instant.now();
        List<ServiceHealth> rows = new ArrayList<>(SERVICES.length);
        for (int i = 0; i < SERVICES.length; i++) {
            long uptimeSec = BASE_UPTIME_SEC + (long) i * 3_600L; // +1h per service
            // lastSeenAt is "recent" (a few seconds ago), so the UI shows the
            // dot as green / fresh.
            Instant lastSeenAt = checkedAt.minus(5L + i, ChronoUnit.SECONDS);
            rows.add(new ServiceHealth(SERVICES[i], "UP", lastSeenAt, uptimeSec));
        }
        return new SystemHealth(checkedAt, List.copyOf(rows));
    }
}
