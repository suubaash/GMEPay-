package com.gme.pay.registry.client;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
import com.gme.pay.internalauth.InternalAuthHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Production {@link NotificationWebhookClient}: calls the notification-webhook
 * service's {@code POST /v1/webhooks/endpoints} via Spring 6
 * {@link RestClient}. Base URL from
 * {@code gmepay.notification-webhook.base-url} (default the compose-internal
 * {@code http://notification-webhook:8080} — every service listens on 8080
 * inside compose/Kubernetes via {@code SERVER_PORT}; 8085 is only
 * notification-webhook's standalone {@code application.properties} default, and
 * the fleet/standalone runs pass the base-url explicitly) — the same
 * conditional/@Primary wiring pattern as
 * {@link com.gme.pay.registry.kyb.RestKybClient} and the BFF's
 * {@code RestConfigRegistryClient}.
 *
 * <h2>Selector: this is the DEFAULT (gap T1-1)</h2>
 *
 * <p>{@code gmepay.notification-webhook.client} selects the transport and this
 * client wins when it says {@code rest} OR is absent
 * ({@code matchIfMissing = true}). The selector was never set for
 * config-registry in any environment, so
 * {@link StubNotificationWebhookClient} (previously
 * {@code matchIfMissing = true}) minted webhook signing secrets that
 * notification-webhook had never seen — every partner webhook signature would
 * have failed verification. Defaults inverted; the stub is now opt-in by name.
 *
 * <p>Spring 6 trap (the RestConfigRegistryClient / RestAuditTrailClient /
 * RestPartnerSchemeResolver lesson): with two constructors the {@code @Value}
 * one MUST carry {@code @Autowired} or context startup fails with "ambiguous
 * constructor".
 *
 * <h2>Failure mapping</h2>
 *
 * <ul>
 *   <li>upstream 4xx — re-thrown with status + body preserved: a 400 from the
 *       registration endpoint is a caller bug worth surfacing verbatim;</li>
 *   <li>network failure / 5xx — 502 Bad Gateway. Provisioning runs INSIDE the
 *       partner-activation transaction, and a half-activated partner with no
 *       webhook endpoint is worse than a failed activation the operator
 *       retries — so unlike the prefunding suspension path there is NO
 *       log-and-swallow here; the exception propagates and rolls the
 *       activation back.</li>
 * </ul>
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.notification-webhook.client", havingValue = "rest",
        matchIfMissing = true)
public class RestNotificationWebhookClient implements NotificationWebhookClient {

    private final RestClient restClient;

    @Autowired
    public RestNotificationWebhookClient(
            @Value("${gmepay.notification-webhook.base-url:http://notification-webhook:8080}")
            String baseUrl,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret) {
        this(builderFor(baseUrl, internalSecret).build());
    }

    /**
     * Builds the {@link RestClient.Builder} the production constructor uses: base URL plus, when
     * a secret is configured, the {@code X-Gme-Internal} default header.
     *
     * <p>Gap T5-8: notification-webhook's {@code /v1/webhooks/endpoints/**} surface mints and
     * reveals {@code whsec_} plaintext and is machine-to-machine only, so it now declares the
     * platform internal-auth gate (default OFF until the deployment supplies the secret).
     * Presenting the token here — the same way this service's other gated-peer clients do —
     * means arming that gate is a pure deployment change and never breaks partner activation.
     * A blank secret sends no header, which is exactly today's behaviour.
     *
     * <p>Package-private so a test can bind a {@code MockRestServiceServer} to the very same
     * builder and assert the header really goes on the wire.
     */
    static RestClient.Builder builderFor(String baseUrl, String internalSecret) {
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        }
        return b;
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestNotificationWebhookClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public WebhookEndpointRegistrationView registerEndpoint(
            WebhookEndpointRegistrationCommand command) {
        try {
            return restClient.post()
                    .uri("/v1/webhooks/endpoints")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .retrieve()
                    .body(WebhookEndpointRegistrationView.class);
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(),
                    "notification-webhook rejected the endpoint registration: "
                            + e.getResponseBodyAsString());
        } catch (ResourceAccessException network) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "notification-webhook unreachable: " + network.getMessage());
        }
    }
}
