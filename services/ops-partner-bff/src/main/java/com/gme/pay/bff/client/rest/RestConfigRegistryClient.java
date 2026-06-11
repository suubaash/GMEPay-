package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.bff.client.ConfigRegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Production {@link ConfigRegistryClient}. Talks to config-registry over HTTP
 * via Spring 6 {@link RestClient}. Active when
 * {@code gmepay.config-registry.client=rest} is set; otherwise the in-memory
 * {@link com.gme.pay.bff.client.stub.StubConfigRegistryClient} wins so the BFF
 * still boots standalone for tests / local dev.
 *
 * <p>Endpoint mapping (config-registry/PartnerController.java):
 * <ul>
 *   <li>{@code GET    /v1/partners}                  -> {@link #listPartners()}</li>
 *   <li>{@code GET    /v1/partners/{id}}             -> {@link #getPartner(String)}</li>
 *   <li>{@code POST   /v1/partners}                  -> {@link #createPartner(PartnerCreateRequest)}</li>
 *   <li>{@code PUT    /v1/partners/{id}/rounding-mode} -> {@link #updateRoundingMode(String, String)}</li>
 * </ul>
 *
 * <p>Scheme list ({@link #listSchemes()}) has no config-registry endpoint yet, so
 * we surface an empty list — the Admin schemes view degrades gracefully. When
 * config-registry exposes {@code GET /v1/schemes}, wire it here without changing
 * the contract.
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

    @Override
    public PartnerSummary getPartner(String partnerId) {
        try {
            PartnerResponse response = restClient.get()
                    .uri("/v1/partners/{id}", partnerId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 = unknown partner; collapse to null below.
                    })
                    .body(PartnerResponse.class);
            return toSummary(response);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on getPartner({}): {}", partnerId, network.getMessage());
            return null;
        }
    }

    @Override
    public List<PartnerSummary> listPartners() {
        try {
            List<PartnerResponse> response = restClient.get()
                    .uri("/v1/partners")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PartnerResponse>>() {});
            if (response == null) {
                return List.of();
            }
            return response.stream().map(RestConfigRegistryClient::toSummary).filter(java.util.Objects::nonNull).toList();
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on listPartners: {}", network.getMessage());
            return List.of();
        }
    }

    @Override
    public PartnerSummary createPartner(PartnerCreateRequest request) {
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("partnerId", request.partnerId());
        body.put("type", request.type());
        body.put("settlementCurrency", request.settlementCurrency());
        body.put("settlementRoundingMode", request.settlementRoundingMode());
        try {
            PartnerResponse response = restClient.post()
                    .uri("/v1/partners")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PartnerResponse.class);
            return toSummary(response);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx so the Admin UI can show config-registry's validation message
            // (e.g. duplicate partnerId, bad rounding mode). Unpack the upstream Spring error
            // envelope so the UI sees "partner '...' already exists", not the entire JSON.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    private static String extractUpstreamMessage(org.springframework.web.client.RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return e.getStatusText();
        }
        try {
            JsonNode node = new ObjectMapper().readTree(body);
            JsonNode msg = node.get("message");
            if (msg != null && !msg.isNull()) {
                return msg.asText();
            }
            JsonNode err = node.get("error");
            if (err != null && !err.isNull()) {
                return err.asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return body;
    }

    @Override
    public PartnerSummary updateRoundingMode(String partnerId, String mode) {
        try {
            PartnerResponse response = restClient.put()
                    .uri("/v1/partners/{id}/rounding-mode", partnerId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("mode", mode))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 = unknown partner; collapse to null below.
                    })
                    .body(PartnerResponse.class);
            return toSummary(response);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on updateRoundingMode({}): {}", partnerId, network.getMessage());
            return null;
        }
    }

    @Override
    public List<SchemeSummary> listSchemes() {
        // No config-registry endpoint for schemes yet. Empty list keeps the UI graceful.
        return Collections.emptyList();
    }

    private static PartnerSummary toSummary(PartnerResponse r) {
        if (r == null || r.partnerId() == null) {
            return null;
        }
        RoundingMode mode = r.settlementRoundingMode() == null
                ? RoundingMode.HALF_UP
                : RoundingMode.valueOf(r.settlementRoundingMode());
        return new PartnerSummary(r.partnerId(), r.type(), r.settlementCurrency(), mode);
    }

    /**
     * Wire shape returned by config-registry. Mirrored locally (MSA rule 5 — do
     * not import config-registry's internal {@code Partner} record). Hibernate
     * serialises the {@link RoundingMode} enum as its name, so this stays
     * String-typed and converts at the boundary above.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PartnerResponse(
            String partnerId,
            String type,
            String settlementCurrency,
            String settlementRoundingMode
    ) {}
}
