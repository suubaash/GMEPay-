package com.gme.pay.prefunding.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Production {@link ConfigRegistryClient}. Talks to config-registry over HTTP via Spring 6
 * {@link RestClient} — {@code POST /v1/change-requests} (ChangeRequestController, ADR-008)
 * proposing {@code {"status":"SUSPENDED"}} on the {@code partner} aggregate with
 * {@code proposedBy='system'}. Active when {@code gmepay.config-registry.client=rest};
 * otherwise the in-memory {@link StubConfigRegistryClient} wins (same activation convention
 * as the BFF's RestConfigRegistryClient).
 *
 * <p>Failures are logged and swallowed: the evaluator runs inside the balance-mutation
 * transaction, and a config-registry outage must never roll back a committed deduction.
 * The BREACH alert row + outbox event still record the incident for operators.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.config-registry.client", havingValue = "rest")
public class RestConfigRegistryClient implements ConfigRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(RestConfigRegistryClient.class);

    private final RestClient restClient;

    @Autowired
    public RestConfigRegistryClient(
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestConfigRegistryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** Wire shape of config-registry's {@code POST /v1/change-requests} create body. */
    record CreateChangeRequest(String aggregateType, String aggregateId, String proposedBy,
                               String payloadJsonb, List<String> appliesToFieldSet) { }

    @Override
    public void proposePartnerSuspension(String partnerCode, String reason) {
        CreateChangeRequest body = new CreateChangeRequest(
                "partner", partnerCode, SYSTEM_PROPOSER,
                "{\"status\":\"SUSPENDED\"}", List.of("status"));
        try {
            restClient.post()
                    .uri("/v1/change-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("proposed system suspension change_request for partner={} reason={}",
                    partnerCode, reason);
        } catch (RestClientException ex) {
            // Never propagate: the breach alert row + outbox event already persist the
            // incident; the suspension proposal is retried by ops, not by this tx.
            log.error("failed to propose suspension change_request for partner={}: {}",
                    partnerCode, ex.getMessage());
        }
    }
}
