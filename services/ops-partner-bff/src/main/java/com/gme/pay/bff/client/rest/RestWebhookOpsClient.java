package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.WebhookOpsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Production {@link WebhookOpsClient}. Talks to notification-webhook over Spring 6
 * {@link RestClient}. Active when {@code gmepay.webhook-ops.client=rest}; otherwise the
 * in-memory {@link com.gme.pay.bff.client.stub.StubWebhookOpsClient} wins.
 *
 * <p>{@link #backlog()} degrades to {@link WebhookBacklog#UNKNOWN} on any upstream fault
 * so the control-tower shows "unknown" not 500; {@link #replay} propagates upstream 4xx.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.webhook-ops.client", havingValue = "rest")
public class RestWebhookOpsClient implements WebhookOpsClient {

    private static final Logger log = LoggerFactory.getLogger(RestWebhookOpsClient.class);

    private final RestClient restClient;

    @Autowired
    public RestWebhookOpsClient(
            @Value("${gmepay.notification-webhook.base-url:http://notification-webhook:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestWebhookOpsClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public WebhookBacklog backlog() {
        try {
            WireBacklog b = restClient.get()
                    .uri("/v1/webhooks/deliveries/backlog")
                    .retrieve()
                    .body(WireBacklog.class);
            return b == null ? WebhookBacklog.UNKNOWN : new WebhookBacklog(b.pending(), b.dlq());
        } catch (RestClientResponseException e) {
            log.warn("notification-webhook backlog error (status={}): {}", e.getStatusCode(), e.getMessage());
            return WebhookBacklog.UNKNOWN;
        } catch (ResourceAccessException e) {
            log.warn("notification-webhook unreachable on backlog: {}", e.getMessage());
            return WebhookBacklog.UNKNOWN;
        }
    }

    @Override
    public ReplayResult replay(String deliveryId, String actor) {
        try {
            WireReplay r = restClient.post()
                    .uri("/v1/webhooks/deliveries/{id}/replay", deliveryId)
                    .retrieve()
                    .body(WireReplay.class);
            if (r == null) {
                return new ReplayResult(deliveryId, "REQUEUED", null);
            }
            return new ReplayResult(deliveryId, r.status() == null ? "REQUEUED" : r.status(), r.detail());
        } catch (RestClientResponseException e) {
            // Unknown delivery id / bad state -> propagate the upstream status + message.
            throw new ResponseStatusException(HttpStatusCode.valueOf(e.getStatusCode().value()), e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireBacklog(Integer pending, Integer dlq) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireReplay(String status, String detail) {}
}
