package com.gme.pay.payment.client.rest;

import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.payment.domain.client.OperationalStatusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
 * <h2>Fail-open vs fail-closed</h2>
 * On an unreachable / erroring config-registry (and no fresh cached value) the policy is configurable
 * via {@code gmepay.ops.status.fail-open} (default {@code true} = fail-OPEN → allow the payment by
 * returning {@link OperationalStatusView#allClear()}). Set it to {@code false} to fail-CLOSED →
 * return a synthetic {@code systemPaused} status so the gate rejects new payments when it cannot
 * confirm the platform is healthy. A last-known-good cached value (even if just past its TTL) is
 * preferred over both, so a brief config-registry blip does not flip policy.
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
            @Value("${gmepay.ops.status.fail-open:true}") boolean failOpen) {
        this.restClient = RestClientSupport.withJavaTime(builder.baseUrl(baseUrl)).build();
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
     * Apply the unreachable policy: prefer a last-known-good cached value (even if just past TTL);
     * otherwise fail-open (allow) or fail-closed (pause) per {@code gmepay.ops.status.fail-open}.
     */
    private OperationalStatusView onUnavailable(Cached lastKnown, String cause) {
        if (lastKnown != null) {
            log.warn("operational-status unreachable ({}) — serving last-known-good", cause);
            return lastKnown.view;
        }
        if (failOpen) {
            log.warn("operational-status unreachable ({}) and no cached value — fail-OPEN (allowing)", cause);
            return OperationalStatusView.allClear();
        }
        log.warn("operational-status unreachable ({}) and no cached value — fail-CLOSED (pausing)", cause);
        return FAIL_CLOSED;
    }

    private record Cached(OperationalStatusView view, long fetchedAtMillis) {
    }
}
