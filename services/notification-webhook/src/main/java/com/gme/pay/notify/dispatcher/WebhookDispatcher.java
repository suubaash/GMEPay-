package com.gme.pay.notify.dispatcher;

import com.gme.pay.notify.domain.RetryPolicy;
import com.gme.pay.notify.domain.WebhookSender;
import com.gme.pay.notify.domain.WebhookSender.WebhookDeliveryResult;
import com.gme.pay.notify.domain.WebhookUrlNotHttpsException;
import com.gme.pay.notify.dispatcher.WebhookTargetResolver.ResolvedTarget;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Drains PENDING {@code webhook_delivery_log} rows and dispatches them to partner
 * endpoints (WBS 8.6 — the missing drain loop that makes UC-WEBHOOK-DELIVERY run).
 *
 * <p>The {@code PaymentApprovedEventHandler} enqueues a PENDING row per
 * {@code payment.approved} event (attempt 0). This scheduled drain picks those rows
 * up, resolves the partner endpoint + signing secret via {@link WebhookTargetResolver},
 * delivers through {@link WebhookSender} (HMAC-signed), and advances the row in place:
 * DELIVERED on 2xx, or back to PENDING for a later retry (honouring
 * {@link RetryPolicy} backoff), promoting to DLQ once attempts are exhausted.
 *
 * <p>Disabled by default ({@code gmepay.webhook.dispatcher.enabled=true} to enable) so
 * H2-only unit slices and local dev never attempt outbound delivery. Enable it
 * alongside a reachable endpoint registration and a resolvable signing secret.
 *
 * <p>Single-instance assumption: with multiple instances a row could be dispatched
 * twice in a window. A distributed lock (e.g. ShedLock) is a follow-up before running
 * more than one dispatcher replica.
 */
@Service
@ConditionalOnProperty(name = "gmepay.webhook.dispatcher.enabled", havingValue = "true")
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookSender sender;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookPersistenceService persistence;
    private final WebhookTargetResolver targetResolver;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public WebhookDispatcher(WebhookSender sender,
                             WebhookDeliveryRepository deliveryRepository,
                             WebhookPersistenceService persistence,
                             WebhookTargetResolver targetResolver,
                             RetryPolicy retryPolicy,
                             Clock clock) {
        this.sender = Objects.requireNonNull(sender);
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository);
        this.persistence = Objects.requireNonNull(persistence);
        this.targetResolver = Objects.requireNonNull(targetResolver);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Scheduled drain. Default cadence 30s (override
     * {@code gmepay.webhook.dispatcher.interval-ms}); first run after a short delay so
     * the context is fully up.
     */
    @Scheduled(
            fixedDelayString = "${gmepay.webhook.dispatcher.interval-ms:30000}",
            initialDelayString = "${gmepay.webhook.dispatcher.initial-delay-ms:5000}")
    public void drainPending() {
        List<WebhookDeliveryEntity> pending =
                deliveryRepository.findByStatus(WebhookPersistenceService.STATUS_PENDING);
        if (pending.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        int dispatched = 0;
        for (WebhookDeliveryEntity row : pending) {
            try {
                if (dispatchOne(row, now)) {
                    dispatched++;
                }
            } catch (RuntimeException e) {
                // Never let one bad row stall the drain; it stays PENDING for next cycle.
                log.error("webhook dispatch error on id={} webhookId={}: {}",
                        row.getId(), row.getWebhookId(), e.getMessage(), e);
            }
        }
        if (dispatched > 0) {
            log.info("webhook dispatcher: attempted {} of {} PENDING rows", dispatched, pending.size());
        }
    }

    /**
     * @return {@code true} if a delivery was attempted, {@code false} if skipped
     *         (backoff not elapsed, or target unresolved).
     */
    private boolean dispatchOne(WebhookDeliveryEntity row, Instant now) {
        // Respect retry backoff: if a prior attempt failed, wait until its window elapses.
        if (row.getLastAttemptedAt() != null && row.getAttempt() >= 1) {
            Instant nextAttemptAt = retryPolicy.nextAttemptAt(
                    Math.min(row.getAttempt(), RetryPolicy.MAX_ATTEMPTS), row.getLastAttemptedAt());
            if (now.isBefore(nextAttemptAt)) {
                return false;
            }
        }

        Optional<ResolvedTarget> target = targetResolver.resolve(row);
        if (target.isEmpty()) {
            // Resolver already logged why; leave PENDING so delivery resumes later.
            return false;
        }

        int attempt = row.getAttempt() + 1;
        byte[] payloadBytes = row.getPayload().getBytes(StandardCharsets.UTF_8);

        WebhookDeliveryResult result;
        try {
            result = sender.sendWithAttempt(
                    row.getWebhookId(),
                    row.getEventType(),
                    target.get().url(),
                    payloadBytes,
                    target.get().secret(),
                    attempt);
        } catch (WebhookUrlNotHttpsException e) {
            // A non-HTTPS endpoint is a hard config error, not a transient failure.
            persistence.markAttemptFailedOrDlq(row, attempt, "non-HTTPS endpoint: " + e.getMessage());
            return true;
        }

        if (result.success()) {
            persistence.markDelivered(row, attempt);
            log.debug("webhook delivered: webhookId={} attempt={} status={}",
                    row.getWebhookId(), attempt, result.httpStatus());
        } else {
            persistence.markAttemptFailedOrDlq(row, attempt, result.responseBody());
            log.debug("webhook attempt failed: webhookId={} attempt={} status={}",
                    row.getWebhookId(), attempt, result.httpStatus());
        }
        return true;
    }
}
