package com.gme.pay.notify.domain;

import com.gme.pay.notify.signing.WebhookSigningService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain service that orchestrates a single webhook delivery attempt.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Add HMAC-SHA256 signature header ({@code X-GME-Webhook-Signature})</li>
 *   <li>Add timestamp header ({@code X-GME-Webhook-Timestamp})</li>
 *   <li>Add event-id header ({@code X-GME-Event-ID})</li>
 *   <li>Reject non-HTTPS target URLs before any network I/O</li>
 *   <li>Return a {@link WebhookDeliveryResult} that the retry/DLQ policy can act on</li>
 * </ul>
 *
 * <p>Actual HTTP transport is delegated to a {@link WebhookHttpClient} interface so
 * the domain stays framework-free and testable.
 */
public class WebhookSender {

    private final WebhookSigningService signingService;
    private final WebhookHttpClient httpClient;
    private final Clock clock;

    public WebhookSender(WebhookSigningService signingService,
                         WebhookHttpClient httpClient,
                         Clock clock) {
        this.signingService = Objects.requireNonNull(signingService);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Attempts to deliver {@code payload} to {@code targetUrl} using the given signing secret.
     *
     * @param eventId   globally unique event identifier (e.g. {@code evt_01HXXX...})
     * @param targetUrl HTTPS URL of the partner endpoint
     * @param payload   serialized JSON payload bytes
     * @param secret    plaintext HMAC signing secret (zeroed from memory after use)
     * @return result containing HTTP status or error detail
     * @throws WebhookUrlNotHttpsException if {@code targetUrl} does not start with {@code https://}
     */
    public WebhookDeliveryResult send(String eventId, String targetUrl,
                                      byte[] payload, String secret) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(targetUrl, "targetUrl must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(secret, "secret must not be null");

        if (!targetUrl.startsWith("https://")) {
            throw new WebhookUrlNotHttpsException(
                    "Webhook URL must use HTTPS, got: " + targetUrl);
        }

        Instant timestamp = Instant.now(clock);
        String timestampHeader = timestamp.toString();
        String signature = signingService.sign(payload, secret);

        WebhookRequest request = new WebhookRequest(
                targetUrl,
                payload,
                signature,
                timestampHeader,
                eventId
        );

        return httpClient.post(request);
    }

    // -------------------------------------------------------------------------
    // Supporting value types
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of headers + body sent to the partner endpoint.
     */
    public record WebhookRequest(
            String targetUrl,
            byte[] payload,
            String signatureHeader,
            String timestampHeader,
            String eventIdHeader
    ) {}

    /**
     * Outcome of a single delivery attempt.
     */
    public record WebhookDeliveryResult(
            int httpStatus,
            String responseBody,
            long durationMs,
            boolean success
    ) {
        public static WebhookDeliveryResult of(int httpStatus, String responseBody, long durationMs) {
            boolean ok = httpStatus >= 200 && httpStatus < 300;
            return new WebhookDeliveryResult(httpStatus, responseBody, durationMs, ok);
        }

        public static WebhookDeliveryResult failure(String errorDetail, long durationMs) {
            return new WebhookDeliveryResult(-1, errorDetail, durationMs, false);
        }
    }
}
