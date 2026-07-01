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
        return post("/v1/ops/pause", body(actor, reason, null, null));
    }

    @Override
    public OperationalStatusView resume(String actor) {
        return post("/v1/ops/resume", body(actor, null, null, null));
    }

    @Override
    public OperationalStatusView maintenance(String actor, String reason) {
        return post("/v1/ops/maintenance", body(actor, reason, null, null));
    }

    @Override
    public OperationalStatusView suspend(String scope, String ref, String actor, String reason) {
        return post("/v1/ops/suspend", body(actor, reason, scope, ref));
    }

    @Override
    public OperationalStatusView unsuspend(String scope, String ref, String actor) {
        return post("/v1/ops/unsuspend", body(actor, null, scope, ref));
    }

    private OperationalStatusView post(String path, Map<String, String> body) {
        OperationalStatusView view = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(OperationalStatusView.class);
        return view == null ? OperationalStatusView.allClear() : view;
    }

    private static Map<String, String> body(String actor, String reason, String scope, String ref) {
        Map<String, String> m = new HashMap<>();
        if (actor != null) {
            m.put("actor", actor);
        }
        if (reason != null) {
            m.put("reason", reason);
        }
        if (scope != null) {
            m.put("scope", scope);
        }
        if (ref != null) {
            m.put("ref", ref);
        }
        return m;
    }
}
