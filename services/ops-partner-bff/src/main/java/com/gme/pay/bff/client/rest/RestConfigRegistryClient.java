package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerView;
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

import java.util.Collections;
import java.util.List;

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
 * <p>Slice 1 collapsed the wire DTO to the canonical {@link PartnerView}
 * shape — this client deserializes that directly and adapts to the BFF's
 * deprecated {@link PartnerSummary} alias via {@link PartnerSummary#fromView}.
 * Adding a partner field is now a one-line change in {@code lib-api-contracts}.
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
            PartnerView view = restClient.get()
                    .uri("/v1/partners/{id}", partnerId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 = unknown partner; collapse to null below.
                    })
                    .body(PartnerView.class);
            return PartnerSummary.fromView(view);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on getPartner({}): {}", partnerId, network.getMessage());
            return null;
        }
    }

    @Override
    public List<PartnerSummary> listPartners() {
        try {
            List<PartnerView> response = restClient.get()
                    .uri("/v1/partners")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PartnerView>>() {});
            if (response == null) {
                return List.of();
            }
            return response.stream()
                    .map(PartnerSummary::fromView)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on listPartners: {}", network.getMessage());
            return List.of();
        }
    }

    @Override
    public PartnerSummary createPartner(PartnerCreateRequest request) {
        // Adapt the BFF's deprecated four-field request to the canonical
        // PartnerCommand.CreateDraft surface config-registry's POST now accepts.
        // Identity-step fields ride the canonical payload as null — the legacy
        // form does not carry them.
        com.gme.pay.contracts.PartnerCommand.CreateDraft body = request.toCreateDraft();
        try {
            PartnerView view = restClient.post()
                    .uri("/v1/partners")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PartnerView.class);
            return PartnerSummary.fromView(view);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx so the Admin UI can show config-registry's validation message
            // (e.g. duplicate partnerCode, bad rounding mode). Unpack the upstream Spring error
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
            PartnerView view = restClient.put()
                    .uri("/v1/partners/{id}/rounding-mode", partnerId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("mode", mode))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 = unknown partner; collapse to null below.
                    })
                    .body(PartnerView.class);
            return PartnerSummary.fromView(view);
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

    // -------- Slice 1 (1C.2) draft endpoints (ADR-012) -----------------------

    @Override
    public PartnerView createDraft(PartnerCommand.CreateDraft request) {
        try {
            return restClient.post()
                    .uri("/v1/partners/draft")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PartnerView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Surface upstream 4xx (duplicate partner_code → 409, validation → 400)
            // through to the Admin UI with the upstream message preserved.
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public PartnerView patchDraftStep1(String partnerCode, PartnerCommand.UpdateStep1 request) {
        try {
            return restClient.patch()
                    .uri("/v1/partners/draft/{partnerCode}/step-1", partnerCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PartnerView.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(), extractUpstreamMessage(e));
        }
    }

    @Override
    public PartnerView getDraft(String partnerCode) {
        try {
            return restClient.get()
                    .uri("/v1/partners/draft/{partnerCode}", partnerCode)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 = unknown draft; collapse to null below.
                    })
                    .body(PartnerView.class);
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on getDraft({}): {}",
                    partnerCode, network.getMessage());
            return null;
        }
    }

    @Override
    public List<PartnerView> listDrafts() {
        try {
            List<PartnerView> response = restClient.get()
                    .uri("/v1/partners/drafts")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PartnerView>>() {});
            return response == null ? List.of() : response;
        } catch (ResourceAccessException network) {
            log.warn("config-registry unreachable on listDrafts: {}", network.getMessage());
            return List.of();
        }
    }
}
