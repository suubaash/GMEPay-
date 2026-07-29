package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.WebhookOpsClient;
import com.gme.pay.internalauth.InternalAuthHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * Production {@link WebhookOpsClient}. Talks to notification-webhook over Spring 6
 * {@link RestClient}. Active when {@code gmepay.webhook-ops.client=rest}; otherwise the
 * in-memory {@link com.gme.pay.bff.client.stub.StubWebhookOpsClient} wins.
 *
 * <p>{@link #backlog()} degrades to {@link WebhookBacklog#UNKNOWN} on any upstream fault
 * so the control-tower shows "unknown" not 500; {@link #replay} propagates upstream 4xx.
 *
 * <h2>Endpoint signing health + secret rotation (gap T5-8)</h2>
 *
 * <p>{@link #endpointSigningHealth(Long)} and {@link #rotateEndpointSecret(String, Long)} reach
 * notification-webhook's {@code /v1/webhooks/endpoints/**} provisioning surface — the one that
 * mints and reveals {@code whsec_} plaintext. That surface is internal-only, so this client
 * presents the shared {@code X-Gme-Internal} token exactly the way the BFF's other
 * internal-surface clients do ({@link RestSandboxKeyClient}, {@link RestPrefundingClient}): a
 * configured secret becomes a default header, a blank one sends nothing and logs a WARN
 * (fail-closed once the upstream gate is armed — never a bypass).
 *
 * <h2>Never logging the rotated secret</h2>
 *
 * <p>{@link #rotateEndpointSecret} returns a one-time plaintext. It is neither logged nor
 * stored here — not on the success path and not in any error branch (upstream faults are logged
 * by endpoint id and status only). The BFF-side response path is additionally covered by
 * {@code IssuedCredentialBundleLogMaskingFilter}.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.webhook-ops.client", havingValue = "rest")
public class RestWebhookOpsClient implements WebhookOpsClient {

    private static final Logger log = LoggerFactory.getLogger(RestWebhookOpsClient.class);

    private final RestClient restClient;

    @Autowired
    public RestWebhookOpsClient(
            @Value("${gmepay.notification-webhook.base-url:http://notification-webhook:8080}") String baseUrl,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret) {
        this(builderFor(baseUrl, internalSecret).build());
    }

    /**
     * Builds the {@link RestClient.Builder} the production constructor uses: base URL plus, when
     * a secret is configured, the {@code X-Gme-Internal} default header. Package-private so a
     * test can bind a {@code MockRestServiceServer} to the very same builder and assert the
     * header really goes on the wire instead of trusting a hand-built client.
     */
    static RestClient.Builder builderFor(String baseUrl, String internalSecret) {
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.internal-auth.secret is blank — webhook endpoint rotation / "
                            + "signing-health calls will carry no {} header, so a gated "
                            + "notification-webhook will refuse them (401). Set "
                            + "GMEPAY_INTERNAL_AUTH_SECRET.",
                    InternalAuthHeaders.INTERNAL_TOKEN);
        }
        return b;
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

    @Override
    public List<EndpointSigningHealth> endpointSigningHealth(Long partnerId) {
        try {
            UriComponentsBuilder uri =
                    UriComponentsBuilder.fromPath("/v1/webhooks/endpoints/signing-health");
            if (partnerId != null) {
                uri.queryParam("partnerId", partnerId);
            }
            List<EndpointSigningHealth> rows = restClient.get()
                    .uri(uri.build().toUriString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<EndpointSigningHealth>>() {});
            return rows == null ? List.of() : rows;
        } catch (RestClientResponseException e) {
            log.warn("notification-webhook signing-health error (partnerId={}, status={})",
                    partnerId, e.getStatusCode());
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("notification-webhook unreachable on signing-health (partnerId={}): {}",
                    partnerId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public RotatedWebhookSecret rotateEndpointSecret(String endpointId, Long overlapMinutes) {
        UriComponentsBuilder uri = UriComponentsBuilder
                .fromPath("/v1/webhooks/endpoints/{endpointId}/rotate-secret");
        if (overlapMinutes != null) {
            uri.queryParam("overlapMinutes", overlapMinutes);
        }
        try {
            RotatedWebhookSecret rotated = restClient.post()
                    .uri(uri.buildAndExpand(endpointId).toUriString())
                    .retrieve()
                    .body(RotatedWebhookSecret.class);
            if (rotated == null || rotated.signingSecretPlaintext() == null) {
                // A rotation with no secret in it is not a rotation — never report success,
                // because the operator would believe they had a value to hand the partner.
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "notification-webhook returned no signing secret for the rotation");
            }
            return rotated;
        } catch (RestClientResponseException e) {
            // Status + endpoint id only. The body of a FAILED rotation carries no secret, but
            // logging upstream bodies on this path is a habit we are not going to form.
            log.warn("notification-webhook rejected the secret rotation for endpointId={} "
                    + "(status={})", endpointId, e.getStatusCode());
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(e.getStatusCode().value()),
                    "notification-webhook rejected the webhook secret rotation: "
                            + e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            log.warn("notification-webhook unreachable rotating endpointId={}: {}",
                    endpointId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "notification-webhook unreachable");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireBacklog(Integer pending, Integer dlq) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireReplay(String status, String detail) {}
}
