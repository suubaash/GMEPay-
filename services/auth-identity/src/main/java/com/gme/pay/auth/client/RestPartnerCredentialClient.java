package com.gme.pay.auth.client;

import com.gme.pay.auth.domain.PartnerCredentialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Production {@link PartnerCredentialPort} adapter.
 *
 * Calls config-registry's public partner endpoint
 * (GET {@code /v1/partners/{id}}) using Spring 6's {@link RestClient}.
 *
 * <p>Per MSA rules (INTER_SERVICE_CONTRACTS.md): auth-identity never reads
 * the config-registry database; it calls the published API and maps the
 * response into this service's local {@link PartnerCredentialPort.ResolvedCredential}
 * value object.</p>
 *
 * <p>Marked {@code @Primary} so it overrides the stub bean defined in
 * {@link com.gme.pay.auth.config.AuthConfig} (which remains as a fallback
 * for tests / local development).</p>
 *
 * <p>The {@code apiKey} parameter is treated as the partner identifier in
 * the URL path. This matches the auth-identity contract: the X-API-Key
 * header is the partner's external key, which config-registry indexes.</p>
 */
@Component
@Primary
public class RestPartnerCredentialClient implements PartnerCredentialPort {

    private static final Logger log = LoggerFactory.getLogger(RestPartnerCredentialClient.class);

    private final RestClient restClient;

    @Autowired
    public RestPartnerCredentialClient(
            @Value("${gme.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor used by tests to inject a pre-built RestClient. */
    RestPartnerCredentialClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<ResolvedCredential> findActiveByApiKey(String apiKey) {
        try {
            PartnerResponse response = restClient.get()
                    .uri("/v1/partners/{id}", apiKey)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 / 401 from config-registry = unknown or inactive partner.
                        // Do NOT throw — we want Optional.empty() out of the port.
                    })
                    .body(PartnerResponse.class);

            if (response == null || response.partnerId() == null || response.hmacSecret() == null) {
                return Optional.empty();
            }
            // The port contract treats absence of an isActive=true credential as "not found".
            if (response.active() != null && !response.active()) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedCredential(response.partnerId(), response.hmacSecret()));
        } catch (ResourceAccessException network) {
            // Network failure: do not log the api key value (potential PII / credential leak).
            log.warn("config-registry unreachable while resolving partner credential: {}",
                    network.getMessage());
            return Optional.empty();
        }
    }

    /**
     * DTO matching config-registry's GET /v1/partners/{id} response shape.
     * Mirrored locally because importing config-registry's internal entity
     * would violate MSA rule 5 (service-owned domain models stay private).
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record PartnerResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("partnerId") Long partnerId,
            @com.fasterxml.jackson.annotation.JsonProperty("hmacSecret") String hmacSecret,
            @com.fasterxml.jackson.annotation.JsonProperty("active")    Boolean active
    ) {}
}
