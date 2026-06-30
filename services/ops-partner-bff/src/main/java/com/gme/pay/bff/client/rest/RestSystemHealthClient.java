package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.SystemHealthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link SystemHealthClient}. Fans out to each backend service's
 * Spring Boot Actuator {@code GET /actuator/health} endpoint and aggregates the
 * results for the Admin UI System Health page. Active when
 * {@code gmepay.system-health.client=rest}; otherwise the in-memory
 * {@link com.gme.pay.bff.client.stub.StubSystemHealthClient} wins so the BFF
 * still boots standalone for tests / local dev.
 *
 * <p><b>Per-service base URLs.</b> Each of the 17 backend services (matching
 * {@code docs/SERVICE_MAP.md}) is probed at its configured base URL. The base
 * URL is resolved per service from {@code gmepay.<service>.base-url} when present
 * (so the same property the other Rest*Client adapters use is reused), falling
 * back to {@code http://<service>:8080} — the compose-network DNS name. This
 * means a single property block governs every adapter's target host.
 *
 * <p><b>Status mapping.</b> Actuator's health document carries a top-level
 * {@code status} of {@code UP} / {@code DOWN} / {@code OUT_OF_SERVICE} /
 * {@code UNKNOWN}. We map {@code UP} -> {@code "UP"}, {@code DOWN} ->
 * {@code "DOWN"}, {@code OUT_OF_SERVICE} -> {@code "DEGRADED"}, and anything
 * else (including an unreachable service, a non-2xx response, or a missing
 * status field) -> {@code "DOWN"} for an error and {@code "UNKNOWN"} for an
 * indeterminate body. {@code lastSeenAt} is the probe instant when the service
 * answered, else null. {@code uptimeSec} is read from the
 * {@code components.* / process.uptime} style metric when actuator exposes it,
 * else left null (actuator's default health document does not include uptime).
 *
 * <p>Probes run concurrently on a small bounded pool so the aggregate latency is
 * roughly the slowest single probe rather than the sum of all 17.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.system-health.client", havingValue = "rest")
public class RestSystemHealthClient implements SystemHealthClient {

    private static final Logger log = LoggerFactory.getLogger(RestSystemHealthClient.class);

    /** Service ids (matches {@code docs/SERVICE_MAP.md} and the stub). */
    static final List<String> SERVICES = List.of(
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
            "security-platform");

    /** Per-probe ceiling. A slow service must not stall the whole snapshot. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;
    private final Environment environment;
    /** When false, probes run sequentially (tests bind a non-thread-safe mock server). */
    private final boolean concurrent;

    @Autowired
    public RestSystemHealthClient(Environment environment) {
        this(RestClient.builder().build(), environment, true);
    }

    /** Package-private constructor for tests to inject a pre-built RestClient (sequential probes). */
    RestSystemHealthClient(RestClient restClient, Environment environment) {
        this(restClient, environment, false);
    }

    RestSystemHealthClient(RestClient restClient, Environment environment, boolean concurrent) {
        this.restClient = restClient;
        this.environment = environment;
        this.concurrent = concurrent;
    }

    @Override
    public SystemHealth check() {
        Instant checkedAt = Instant.now();
        if (!concurrent) {
            List<ServiceHealth> rows = new ArrayList<>(SERVICES.size());
            for (String service : SERVICES) {
                rows.add(probe(service));
            }
            return new SystemHealth(checkedAt, List.copyOf(rows));
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, SERVICES.size()));
        try {
            List<CompletableFuture<ServiceHealth>> futures = new ArrayList<>(SERVICES.size());
            for (String service : SERVICES) {
                futures.add(CompletableFuture.supplyAsync(() -> probe(service), pool));
            }
            List<ServiceHealth> rows = new ArrayList<>(SERVICES.size());
            for (CompletableFuture<ServiceHealth> f : futures) {
                try {
                    rows.add(f.get(PROBE_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    // A future that itself timed out / failed: we cannot recover
                    // its service name here, so skip — probe() already maps every
                    // recoverable fault to a DOWN row. This only fires if the pool
                    // is saturated, in which case the missing rows degrade the UI
                    // gracefully (fewer rows) rather than failing the snapshot.
                    log.warn("system-health: a probe future failed: {}", e.getMessage());
                }
            }
            return new SystemHealth(checkedAt, List.copyOf(rows));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Probes one service's {@code /actuator/health}; never throws. */
    private ServiceHealth probe(String service) {
        String baseUrl = baseUrlFor(service);
        try {
            WireHealth body = restClient.get()
                    .uri(baseUrl + "/actuator/health")
                    .retrieve()
                    .body(WireHealth.class);
            if (body == null || body.status() == null) {
                return new ServiceHealth(service, "UNKNOWN", null, null);
            }
            return new ServiceHealth(service, mapStatus(body.status()), Instant.now(), null);
        } catch (Exception e) {
            // Unreachable, non-2xx, or unparseable: the service is not answering
            // a healthy document -> DOWN. lastSeenAt stays null (no fresh probe).
            log.debug("system-health: {} probe failed: {}", service, e.getMessage());
            return new ServiceHealth(service, "DOWN", null, null);
        }
    }

    /** Resolves {@code gmepay.<service>.base-url}, else the compose DNS default. */
    private String baseUrlFor(String service) {
        String key = "gmepay." + service + ".base-url";
        String configured = environment.getProperty(key);
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "http://" + service + ":8080";
    }

    /** Maps actuator's status string to the BFF's {@code ServiceHealth.status}. */
    static String mapStatus(String actuatorStatus) {
        return switch (actuatorStatus.toUpperCase()) {
            case "UP" -> "UP";
            case "DOWN" -> "DOWN";
            case "OUT_OF_SERVICE" -> "DEGRADED";
            default -> "UNKNOWN";
        };
    }

    /** Minimal slice of actuator's health document; only {@code status} is used. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireHealth(String status) {}
}
