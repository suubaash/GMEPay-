package com.gme.pay.bff.alert.paging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Production {@link PagingPort}: POSTs the {@link PageRequest} as JSON to a single
 * configured on-call webhook URL ({@code gmepay.ops.paging.webhook-url}). One generic
 * webhook drives Slack incoming webhooks / PagerDuty Events API / Opsgenie / MS Teams —
 * NO vendor is hardcoded (ADR-015: vendor-agnostic, no cloud SDK). The on-call side
 * templates against the stable {@link PageRequest} shape.
 *
 * <p><b>Active only when a URL is configured</b>: {@link PagingConfig} registers this as the
 * {@link PagingPort} bean only when {@code gmepay.ops.paging.webhook-url} is set; with no URL
 * the {@link LogPagingAdapter} fallback wins so the service is functional out of the box.
 *
 * <p><b>Resilience.</b> Explicit connect+read timeout ({@code gmepay.ops.paging.timeout-ms},
 * default 3000ms) and a couple of retries on 5xx / transport error
 * ({@code gmepay.ops.paging.max-attempts}, default 3). Never throws — a final failure is
 * returned as {@link PageOutcome#failed}. 4xx is treated as a permanent config error
 * (bad URL / payload) and not retried.
 */
public class WebhookPagingAdapter implements PagingPort {

    static final String CHANNEL = "webhook";

    private static final Logger log = LoggerFactory.getLogger(WebhookPagingAdapter.class);

    private final RestClient restClient;
    private final String webhookUrl;
    private final int maxAttempts;

    public WebhookPagingAdapter(String webhookUrl, long timeoutMs, int maxAttempts) {
        this(RestClient.builder().requestFactory(timeoutFactory(timeoutMs)).build(),
                webhookUrl, maxAttempts);
    }

    /** Package-private constructor for tests (inject a MockRestServiceServer-bound client). */
    WebhookPagingAdapter(RestClient restClient, String webhookUrl, int maxAttempts) {
        this.restClient = restClient;
        this.webhookUrl = webhookUrl;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    private static ClientHttpRequestFactory timeoutFactory(long timeoutMs) {
        Duration t = Duration.ofMillis(timeoutMs <= 0 ? 3000 : timeoutMs);
        JdkClientHttpRequestFactory f =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(t).build());
        f.setReadTimeout(t);
        return f;
    }

    @Override
    public PageOutcome page(PageRequest request) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                restClient.post()
                        .uri(webhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();
                if (attempt > 1) {
                    log.info("ops paging delivered on attempt {} (type={} subjectRef={})",
                            attempt, request.alertType(), request.subjectRef());
                }
                return PageOutcome.delivered(CHANNEL);
            } catch (org.springframework.web.client.RestClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    // Permanent (bad URL / payload) — do not retry.
                    log.warn("ops paging rejected {} (no retry): {}", e.getStatusCode(), e.getMessage());
                    return PageOutcome.failed(CHANNEL, "http " + e.getStatusCode().value());
                }
                last = e; // 5xx — retry
            } catch (RuntimeException e) {
                last = e; // transport (timeout / connect) — retry
            }
            log.warn("ops paging attempt {}/{} failed (type={} subjectRef={}): {}",
                    attempt, maxAttempts, request.alertType(), request.subjectRef(),
                    last == null ? "?" : last.getMessage());
        }
        return PageOutcome.failed(CHANNEL, last == null ? "unknown" : last.getMessage());
    }
}
