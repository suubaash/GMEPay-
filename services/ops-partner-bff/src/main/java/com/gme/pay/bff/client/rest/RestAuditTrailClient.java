package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.AuditTrailClient;
import com.gme.pay.contracts.AuditEntryView;
import com.gme.pay.contracts.PageView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Production implementation of {@link AuditTrailClient}. Calls config-registry's
 * {@code GET /v1/audit} endpoint via Spring 6 {@link RestClient}.
 *
 * <p>Active when {@code gmepay.config-registry.client=rest} (same property that
 * activates {@link RestConfigRegistryClient}). Both REST clients share the same
 * conditional to avoid split-brain: if config-registry is up, both clients go live.
 *
 * <p>On network errors the client returns an empty page and logs a warning rather
 * than propagating the exception — the audit trail is read-only and a connectivity
 * blip should degrade gracefully, not crash the Admin UI.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.config-registry.client", havingValue = "rest")
public class RestAuditTrailClient implements AuditTrailClient {

    private static final Logger log = LoggerFactory.getLogger(RestAuditTrailClient.class);

    /** Type token for deserializing the {@code PageView<AuditEntryView>} response. */
    private static final ParameterizedTypeReference<PageView<AuditEntryView>> PAGE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public RestAuditTrailClient(
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for unit tests to inject a pre-built RestClient. */
    RestAuditTrailClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PageView<AuditEntryView> list(String aggregateType, String aggregateId,
                                          int page, int size) {
        try {
            PageView<AuditEntryView> result = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/audit")
                            .queryParam("aggregateType", aggregateType)
                            .queryParam("aggregateId", aggregateId)
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .retrieve()
                    .body(PAGE_TYPE);
            return result != null
                    ? result
                    : new PageView<>(List.of(), page, size, 0L);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on auditTrail({}/{}): {}",
                    aggregateType, aggregateId, network.getMessage());
            return new PageView<>(List.of(), page, size, 0L);
        }
    }
}
