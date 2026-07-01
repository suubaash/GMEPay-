package com.gme.pay.payment.client.rest;

import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.payment.domain.client.OperationalStatusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * REST {@link OperationalStatusClient} that reads config-registry's ops read model:
 * <pre>GET {config-registry}/v1/ops/operational-status → OperationalStatusView</pre>
 *
 * <p>Gated on {@code gmepay.config-registry.base-url} via {@code @ConditionalOnProperty}: when the
 * base-url is absent this bean is not created and the in-process
 * {@link FixtureOperationalStatusClient} (all-clear) takes over.
 *
 * <h2>Short in-memory cache</h2>
 * The gate is on the hot payment path, so we cache the last good status for
 * {@code gmepay.ops.status.cache-ttl-millis} (default 3s) to avoid a config-registry round-trip per
 * payment. A stale-but-recent status is acceptable for a kill switch: an operator pause takes effect
 * within the TTL.
 *
 * <h2>Fail-CLOSED for security (defect #4 fix)</h2>
 * The operational gate is a <b>kill switch</b>: {@code systemPaused}, {@code maintenanceMode} and the
 * partner/scheme/route <b>suspension</b> lists are SECURITY signals. If config-registry is unreachable
 * (including a client timeout — see below) <b>and there is no fresh/last-known-good cached value</b>,
 * we can no longer confirm the platform is healthy, so the safe default is to <b>DENY</b> new work
 * (fail-CLOSED) — returning a synthetic {@code systemPaused} status so the gate rejects the new
 * authorization with the existing {@code SYSTEM_PAUSED} code. Failing OPEN here would let a
 * suspended/paused partner transact during an outage, defeating the kill switch (safety inversion).
 *
 * <p>This security-fail-closed behavior is the <b>default</b>, config-overridable via
 * {@code gmepay.ops.status.fail-open} (default {@code false} = fail-CLOSED for the security flags).
 * Set it to {@code true} only to restore the old fail-OPEN-on-outage behavior (allow by returning
 * {@link OperationalStatusView#allClear()}), which trades kill-switch safety for availability.
 *
 * <p>A last-known-good cached value (even if just past its TTL) is always preferred over either
 * policy, so a brief config-registry blip does not flip policy — only a genuinely no-signal cold
 * executor fails closed.
 *
 * <h2>Hard client timeout (defect #4 fix)</h2>
 * The gate is on the hot pay path, so the RestClient is given an explicit connect + read timeout
 * ({@code gmepay.ops.status.connect-timeout-millis} / {@code read-timeout-millis}, default 500ms each)
 * so a HUNG config-registry cannot stall authorization. A timeout surfaces as a
 * {@code ResourceAccessException} and is therefore treated as "unreachable" → the fail-closed-security
 * rule above applies.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "gmepay.config-registry", name = "base-url")
public class RestOperationalStatusClient implements OperationalStatusClient {

    private static final Logger log = LoggerFactory.getLogger(RestOperationalStatusClient.class);

    /** Synthetic fail-closed status: pauses new payments when the status cannot be confirmed. */
    private static final OperationalStatusView FAIL_CLOSED = new OperationalStatusView(
            true, false, java.util.List.of(), java.util.List.of(), java.util.List.of(),
            "operational status unreachable (fail-closed)", null);

    private final RestClient restClient;
    private final long cacheTtlMillis;
    private final boolean failOpen;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    @Autowired
    public RestOperationalStatusClient(
            RestClient.Builder builder,
            @Value("${gmepay.config-registry.base-url}") String baseUrl,
            @Value("${gmepay.ops.status.cache-ttl-millis:3000}") long cacheTtlMillis,
            @Value("${gmepay.ops.status.fail-open:false}") boolean failOpen,
            @Value("${gmepay.ops.status.connect-timeout-millis:500}") long connectTimeoutMillis,
            @Value("${gmepay.ops.status.read-timeout-millis:500}") long readTimeoutMillis) {
        // Hard connect + read timeout so a HUNG config-registry cannot stall the pay path: a timeout
        // surfaces as ResourceAccessException → treated as unreachable → fail-closed-security applies.
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .withReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.restClient = RestClientSupport.withJavaTime(builder.baseUrl(baseUrl))
                .requestFactory(ClientHttpRequestFactories.get(timeouts))
                .build();
        this.cacheTtlMillis = cacheTtlMillis;
        this.failOpen = failOpen;
    }

    /** Test constructor — pre-built RestClient, explicit TTL + policy. */
    RestOperationalStatusClient(RestClient restClient, long cacheTtlMillis, boolean failOpen) {
        this.restClient = restClient;
        this.cacheTtlMillis = cacheTtlMillis;
        this.failOpen = failOpen;
    }

    @Override
    public OperationalStatusView currentStatus() {
        long now = System.currentTimeMillis();
        Cached hit = cache.get();
        if (hit != null && now - hit.fetchedAtMillis < cacheTtlMillis) {
            return hit.view;
        }
        try {
            OperationalStatusView fresh = restClient.get()
                    .uri("/v1/ops/operational-status")
                    .retrieve()
                    .body(OperationalStatusView.class);
            if (fresh == null) {
                return onUnavailable(hit, "empty body");
            }
            cache.set(new Cached(fresh, now));
            return fresh;
        } catch (RuntimeException ex) {
            return onUnavailable(hit, ex.getMessage());
        }
    }

    /**
     * Apply the unreachable policy: prefer a last-known-good cached value (even if just past TTL) so a
     * brief blip does not flip policy; only when there is GENUINELY no signal do we apply the
     * fail-open/fail-closed default. Default is fail-CLOSED for the security flags (defect #4):
     * a cold executor during a config-registry outage must NOT let a suspended/paused partner
     * transact. Overridable to the legacy fail-OPEN via {@code gmepay.ops.status.fail-open=true}.
     */
    private OperationalStatusView onUnavailable(Cached lastKnown, String cause) {
        if (lastKnown != null) {
            log.warn("operational-status unreachable ({}) — serving last-known-good", cause);
            return lastKnown.view;
        }
        if (failOpen) {
            log.warn("operational-status unreachable ({}) and no cached value — fail-OPEN (allowing; "
                    + "kill-switch not enforceable)", cause);
            return OperationalStatusView.allClear();
        }
        log.warn("operational-status unreachable ({}) and no cached value — fail-CLOSED for security "
                + "(denying new authorization)", cause);
        return FAIL_CLOSED;
    }

    private record Cached(OperationalStatusView view, long fetchedAtMillis) {
    }
}
