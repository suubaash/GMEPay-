package com.gme.pay.notify.client.rest;

import com.gme.pay.notify.domain.WebhookHttpClient;
import com.gme.pay.notify.domain.WebhookSender.WebhookDeliveryResult;
import com.gme.pay.notify.domain.WebhookSender.WebhookRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Objects;

/**
 * Concrete {@link WebhookHttpClient} — the outbound HTTP transport for webhook
 * delivery, using Spring 6 {@link RestClient}. This is the production transport the
 * domain {@code WebhookSender} delegates to; the domain layer stays framework-free.
 *
 * <p>Active when {@code gmepay.webhook.http-client=rest} (the default). A test can
 * mock {@link WebhookHttpClient} directly, or set the property to anything else to
 * suppress this bean.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>POSTs {@link WebhookRequest#payload()} to {@link WebhookRequest#targetUrl()}
 *       with the signed headers {@code X-GME-Webhook-Signature} /
 *       {@code X-GME-Webhook-Timestamp} / {@code X-GME-Event-ID} that
 *       {@code WebhookSender} prepared.</li>
 *   <li>Applies bounded connect/read timeouts so a slow partner cannot stall the
 *       dispatcher drain loop.</li>
 *   <li><b>Never throws</b>: a non-2xx response is returned as a
 *       {@link WebhookDeliveryResult} carrying the real HTTP status (so the retry/DLQ
 *       policy can act on it); a network error/timeout becomes a
 *       {@link WebhookDeliveryResult#failure}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "gmepay.webhook.http-client", havingValue = "rest", matchIfMissing = true)
public class RestWebhookHttpClient implements WebhookHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RestWebhookHttpClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public RestWebhookHttpClient() {
        this(defaultRestClient());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestWebhookHttpClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public WebhookDeliveryResult post(WebhookRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(request.targetUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-GME-Webhook-Signature", request.signatureHeader())
                    .header("X-GME-Webhook-Timestamp", request.timestampHeader())
                    .header("X-GME-Event-ID", request.eventIdHeader())
                    .body(request.payload())
                    .retrieve()
                    .toEntity(String.class);
            long ms = System.currentTimeMillis() - start;
            return WebhookDeliveryResult.of(response.getStatusCode().value(), response.getBody(), ms);
        } catch (RestClientResponseException http) {
            // 4xx / 5xx from the partner — a real HTTP outcome, not a transport failure.
            long ms = System.currentTimeMillis() - start;
            log.warn("webhook delivery non-2xx: url={} status={} ({}ms)",
                    request.targetUrl(), http.getStatusCode().value(), ms);
            return WebhookDeliveryResult.of(
                    http.getStatusCode().value(), http.getResponseBodyAsString(), ms);
        } catch (ResourceAccessException network) {
            long ms = System.currentTimeMillis() - start;
            log.warn("webhook delivery network error: url={} error={} ({}ms)",
                    request.targetUrl(), network.getMessage(), ms);
            return WebhookDeliveryResult.failure("network error: " + network.getMessage(), ms);
        } catch (RuntimeException unexpected) {
            long ms = System.currentTimeMillis() - start;
            log.error("webhook delivery unexpected error: url={} error={} ({}ms)",
                    request.targetUrl(), unexpected.getMessage(), ms, unexpected);
            return WebhookDeliveryResult.failure("unexpected error: " + unexpected.getMessage(), ms);
        }
    }
}
