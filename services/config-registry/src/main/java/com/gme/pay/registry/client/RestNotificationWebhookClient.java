package com.gme.pay.registry.client;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
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
 * {@link RestClient}. Active when
 * {@code gmepay.notification-webhook.client=rest}; base URL from
 * {@code gmepay.notification-webhook.base-url} (default the compose-internal
 * {@code http://notification-webhook:8085}) — the same conditional/@Primary
 * wiring pattern as {@link com.gme.pay.registry.kyb.RestKybClient} and the
 * BFF's {@code RestConfigRegistryClient}.
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
@ConditionalOnProperty(name = "gmepay.notification-webhook.client", havingValue = "rest")
public class RestNotificationWebhookClient implements NotificationWebhookClient {

    private final RestClient restClient;

    @Autowired
    public RestNotificationWebhookClient(
            @Value("${gmepay.notification-webhook.base-url:http://notification-webhook:8085}")
            String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
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
