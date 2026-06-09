package com.gme.pay.notify.domain;

import com.gme.pay.notify.persistence.WebhookPersistenceService;
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

    /**
     * Optional Phase-1 persistence collaborator. When {@code null} the sender behaves
     * exactly as before (existing unit tests stay green); when non-null each delivery
     * attempt + DLQ promotion is durably recorded.
     */
    private final WebhookPersistenceService persistenceService;

    /**
     * Backwards-compatible constructor — no persistence. Existing callers and tests
     * continue to work; persistence is simply skipped.
     */
    public WebhookSender(WebhookSigningService signingService,
                         WebhookHttpClient httpClient,
                         Clock clock) {
        this(signingService, httpClient, clock, null);
    }

    /**
     * Constructor with the optional Phase-1 persistence collaborator.
     *
     * @param persistenceService may be {@code null} to disable persistence (no-op mode)
     */
    public WebhookSender(WebhookSigningService signingService,
                         WebhookHttpClient httpClient,
                         Clock clock,
                         WebhookPersistenceService persistenceService) {
        this.signingService = Objects.requireNonNull(signingService);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.clock = Objects.requireNonNull(clock);
        this.persistenceService = persistenceService; // optional: may be null
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
        return sendWithAttempt(eventId, null, targetUrl, payload, secret, 1);
    }

    /**
     * Phase-1 persistence-aware variant: in addition to dispatching the webhook this
     * overload records the attempt outcome (and promotes to DLQ on exhaustion) when a
     * {@link WebhookPersistenceService} was supplied at construction time. If no
     * persistence service is wired the behaviour is identical to {@link #send}.
     *
     * @param eventId    globally unique event id (also used as {@code webhook_id})
     * @param eventType  domain event type, e.g. {@code payment.approved}; may be {@code null}
     * @param targetUrl  HTTPS URL of the partner endpoint
     * @param payload    serialized JSON payload bytes
     * @param secret     plaintext HMAC signing secret
     * @param attempt    1-based attempt number for retry accounting
     */
    public WebhookDeliveryResult sendWithAttempt(String eventId,
                                                 String eventType,
                                                 String targetUrl,
                                                 byte[] payload,
                                                 String secret,
                                                 int attempt) {
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

        WebhookDeliveryResult result = httpClient.post(request);

        if (persistenceService != null) {
            String body = new String(payload, StandardCharsets.UTF_8);
            String error = result.success() ? null : result.responseBody();
            persistenceService.recordAttemptAndMaybeDlq(
                    eventId,
                    eventType == null ? "unknown" : eventType,
                    body,
                    attempt,
                    result.success(),
                    error
            );
        }

        return result;
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
