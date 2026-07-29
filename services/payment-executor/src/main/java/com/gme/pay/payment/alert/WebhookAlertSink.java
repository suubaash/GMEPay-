package com.gme.pay.payment.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Concrete {@link AlertSink} that POSTs the alert as JSON to <b>one URL from config</b>
 * ({@code gmepay.alert.sink.webhook-url}) — the only thing an operator has to supply to turn
 * payment-executor's alerts into a page (gap T3-3).
 *
 * <p><b>No vendor is hardcoded and no credential is embedded</b> (ADR-015). One generic webhook POST
 * drives a Slack incoming webhook, a PagerDuty Events v2 integration URL, an Opsgenie/MS-Teams
 * connector, or an in-house receiver; the secret, when there is one, is the opaque path/token inside
 * the URL the operator configures, exactly as ops-partner-bff's {@code WebhookPagingAdapter} already
 * does. The body is the stable {@link OpsAlertPayload} shape
 * ({@code eventType, alertType, severity, subjectRef, detail, occurredAt}) so the receiving side
 * templates against one contract.
 *
 * <p><b>Active only when a URL is configured</b>: {@link AlertSinkConfig} registers this bean under
 * {@code @ConditionalOnProperty}, otherwise {@link LogAlertSink} wins. There is intentionally no
 * default URL — a blank one would make every delivery fail.
 *
 * <p><b>Resilience.</b> Explicit connect + read timeout ({@code gmepay.alert.sink.timeout-ms},
 * default 3000ms) and a bounded retry on 5xx / transport error
 * ({@code gmepay.alert.sink.max-attempts}, default 3). 4xx is a permanent configuration error (wrong
 * URL / rejected body) and is not retried. <b>Never throws</b>: a final failure comes back as
 * {@link AlertDelivery#failed}, is recorded on the {@code ops_alerts} row, and the monitor continues.
 */
public class WebhookAlertSink implements AlertSink {

    /** Value written to {@code ops_alerts.notify_channel}. */
    public static final String CHANNEL = "webhook";

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertSink.class);

    private final RestClient restClient;
    private final String webhookUrl;
    private final int maxAttempts;

    public WebhookAlertSink(String webhookUrl, long timeoutMs, int maxAttempts) {
        this(RestClient.builder().requestFactory(timeoutFactory(timeoutMs)).build(),
                webhookUrl, maxAttempts);
    }

    /** Package-private constructor for tests (inject a MockRestServiceServer-bound client). */
    WebhookAlertSink(RestClient restClient, String webhookUrl, int maxAttempts) {
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
    public AlertDelivery deliver(OpsAlertPayload alert) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                restClient.post()
                        .uri(webhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(alert)
                        .retrieve()
                        .toBodilessEntity();
                if (attempt > 1) {
                    log.info("ops alert notification delivered on attempt {} (type={} subjectRef={})",
                            attempt, alert.alertType(), alert.subjectRef());
                }
                return AlertDelivery.delivered(CHANNEL);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    // Permanent (bad URL / rejected payload) — retrying cannot help.
                    log.warn("ops alert notification rejected {} (no retry): {}",
                            e.getStatusCode(), e.getMessage());
                    return AlertDelivery.failed(CHANNEL, "http " + e.getStatusCode().value());
                }
                last = e; // 5xx — retry
            } catch (RuntimeException e) {
                last = e; // transport (timeout / connect) — retry
            }
            log.warn("ops alert notification attempt {}/{} failed (type={} subjectRef={}): {}",
                    attempt, maxAttempts, alert.alertType(), alert.subjectRef(),
                    last == null ? "?" : last.getMessage());
        }
        return AlertDelivery.failed(CHANNEL, last == null ? "unknown" : last.getMessage());
    }
}
