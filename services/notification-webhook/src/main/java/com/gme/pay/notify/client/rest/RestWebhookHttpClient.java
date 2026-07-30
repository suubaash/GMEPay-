package com.gme.pay.notify.client.rest;

import com.gme.pay.notify.domain.WebhookHttpClient;
import com.gme.pay.notify.domain.WebhookSender.WebhookDeliveryResult;
import com.gme.pay.notify.domain.WebhookSender.WebhookRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Default per-delivery budget, and the arithmetic behind lowering the read half.
     *
     * <p>It was 10 s. With the drain's 200-row batch that is up to 2 000 s of sequential work on a
     * 30 s cycle — the reason {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #6 records that the webhook
     * queue does not close even at 1x. Concurrency in {@code WebhookDispatcher} is the larger part of
     * the fix, but per-delivery cost still sets the ceiling: at C concurrent workers the drain rate is
     * C / read-timeout in the worst case, so halving the timeout doubles the floor under that rate.
     *
     * <p>5 s is a budget for a partner's HTTP endpoint to accept a small signed JSON body. An
     * endpoint that needs longer than that is not "slow", it is doing synchronous work behind the
     * webhook, and the correct answer for it is a retry (which this pipeline already does, with
     * backoff and a DLQ) rather than an open socket. Nothing is lost on a timeout: the row stays
     * PENDING with its attempt counted.
     *
     * <p>Both values are properties, because unlike the internal hops this one is partly a statement
     * about what GMEPay+ expects of a <em>partner's</em> infrastructure. If that ever becomes a
     * contractual number rather than an engineering one, it is already configurable.
     */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    @Autowired
    public RestWebhookHttpClient(
            @Value("${gmepay.webhook.http.connect-timeout-millis:5000}") long connectTimeoutMillis,
            @Value("${gmepay.webhook.http.read-timeout-millis:5000}") long readTimeoutMillis) {
        this(timeoutBoundedRestClient(connectTimeoutMillis, readTimeoutMillis));
    }

    /** No-arg constructor for direct instantiation in tests//fixtures — ships the defaults. */
    public RestWebhookHttpClient() {
        this(timeoutBoundedRestClient(
                DEFAULT_CONNECT_TIMEOUT.toMillis(), DEFAULT_READ_TIMEOUT.toMillis()));
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestWebhookHttpClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    private static RestClient timeoutBoundedRestClient(long connectTimeoutMillis, long readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeoutMillis);
        factory.setReadTimeout((int) readTimeoutMillis);
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
