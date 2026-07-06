package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.OpsControlClient;
import com.gme.pay.contracts.OperationalStatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Map;

/**
 * Production {@link OpsControlClient}. Talks to config-registry's ops endpoints over
 * Spring 6 {@link RestClient}. Active when {@code gmepay.ops-control.client=rest};
 * otherwise the in-memory {@link com.gme.pay.bff.client.stub.StubOpsControlClient} wins.
 *
 * <p>Reads degrade to {@link OperationalStatusView#allClear()} on unreachable upstream
 * so the control-tower never 500s on the status section; mutators propagate upstream
 * 4xx as {@link org.springframework.web.server.ResponseStatusException} so a rejected
 * action surfaces its reason.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.ops-control.client", havingValue = "rest")
public class RestOpsControlClient implements OpsControlClient {

    private static final Logger log = LoggerFactory.getLogger(RestOpsControlClient.class);

    private final RestClient restClient;

    @Autowired
    public RestOpsControlClient(
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestOpsControlClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public OperationalStatusView operationalStatus() {
        try {
            OperationalStatusView view = restClient.get()
                    .uri("/v1/ops/operational-status")
                    .retrieve()
                    .body(OperationalStatusView.class);
            return view == null ? OperationalStatusView.allClear() : view;
        } catch (RestClientResponseException e) {
            log.warn("config-registry ops status error (status={}): {}", e.getStatusCode(), e.getMessage());
            return OperationalStatusView.allClear();
        } catch (ResourceAccessException e) {
            log.warn("config-registry unreachable on operational-status: {}", e.getMessage());
            return OperationalStatusView.allClear();
        }
    }

    @Override
    public OperationalStatusView pause(String actor, String reason) {
        return post("/v1/ops/pause", actor, mapOfNonNull("reason", reason));
    }

    @Override
    public OperationalStatusView resume(String actor) {
        return post("/v1/ops/resume", actor, Map.of());
    }

    @Override
    public OperationalStatusView maintenance(String actor, String reason) {
        // The BFF contract is a TOGGLE (matching StubOpsControlClient) but the upstream
        // MaintenanceRequest is explicit {on, reason} — omitting `on` would deserialize to
        // false and silently EXIT maintenance on every call. Read current state to flip it.
        boolean on = !operationalStatus().maintenanceMode();
        Map<String, Object> body = new HashMap<>();
        body.put("on", on);
        if (reason != null) {
            body.put("reason", reason);
        }
        return post("/v1/ops/maintenance", actor, body);
    }

    @Override
    public OperationalStatusView suspend(String scope, String ref, String actor, String reason) {
        Map<String, Object> body = mapOfNonNull("reason", reason);
        body.put("entityType", scope);
        body.put("entityId", ref);
        return post("/v1/ops/suspend", actor, body);
    }

    @Override
    public OperationalStatusView unsuspend(String scope, String ref, String actor) {
        Map<String, Object> body = new HashMap<>();
        body.put("entityType", scope);
        body.put("entityId", ref);
        return post("/v1/ops/unsuspend", actor, body);
    }

    /**
     * OpsControlController reads the operator from the {@code X-Actor} HEADER (not the body) —
     * sending it in the body loses audit attribution upstream.
     */
    private OperationalStatusView post(String path, String actor, Map<String, Object> body) {
        OperationalStatusView view = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (actor != null && !actor.isBlank()) {
                        h.set("X-Actor", actor);
                    }
                })
                .body(body)
                .retrieve()
                .body(OperationalStatusView.class);
        return view == null ? OperationalStatusView.allClear() : view;
    }

    private static Map<String, Object> mapOfNonNull(String key, String value) {
        Map<String, Object> m = new HashMap<>();
        if (value != null) {
            m.put(key, value);
        }
        return m;
    }
}
