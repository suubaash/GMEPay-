package com.gme.pay.notify.dispatcher;

import com.gme.pay.notify.alert.WebhookAlertService;
import com.gme.pay.notify.domain.RetryPolicy;
import com.gme.pay.notify.domain.WebhookSender;
import com.gme.pay.notify.domain.WebhookSender.WebhookDeliveryResult;
import com.gme.pay.notify.domain.WebhookUrlNotHttpsException;
import com.gme.pay.notify.dispatcher.WebhookTargetResolver.ResolvedTarget;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
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
 * <h2>T3-11: what changed, and what a second replica now does</h2>
 *
 * <p>This class used to carry the note "a distributed lock (e.g. ShedLock) is a follow-up before
 * running more than one dispatcher replica". That follow-up is done: {@link SchedulerLock} on
 * {@link #drainPending()} means a second instance's tick returns immediately without reading a row,
 * so the duplicate-webhook failure mode is closed and notification-webhook is no longer pinned to one
 * replica <em>for correctness</em>. It is still effectively one replica for <em>throughput</em>,
 * because the lock means only one instance drains at a time — see below.
 *
 * <p>The other half is the drain rate. {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #6 measured that
 * this loop does not keep up even at 1x: 200 rows delivered <b>sequentially</b>, each up to the HTTP
 * client's read timeout, on a 30 s cycle. The batch is now delivered through a bounded worker pool
 * ({@code gmepay.webhook.dispatcher.concurrency}, default 8) and the cycle is 5 s, which raises the
 * ceiling by roughly the concurrency factor.
 *
 * <p><b>What that does not fix, stated plainly.</b> Rows are still selected in one global
 * {@code ORDER BY created_at} across all partners. One partner whose endpoint times out on every
 * delivery still consumes workers that other partners' rows are waiting for — concurrency raises the
 * number of slow deliveries it takes to stall everyone from 1 to 8, which is a mitigation, not a
 * cure. Closing it properly needs per-endpoint fairness: a per-endpoint circuit breaker that stops
 * selecting rows for an endpoint that is failing, or per-endpoint queues so one partner's backlog
 * cannot occupy another's capacity. That is a design change to the selection query and the retry
 * model, not a parameter, and it is deliberately not attempted here.
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
    /** Max PENDING rows pulled per drain (#92 — bound the fetch). */
    private final int batchSize;
    /**
     * How many deliveries are in flight at once within a single drain (T3-11).
     *
     * <p>Bounded, and bounded deliberately low. Each worker holds one HTTP connection and one
     * database transaction's worth of work, so this is simultaneously a cap on sockets to partner
     * endpoints and on concurrent demand for the Hikari pool (whose default size is 10 —
     * {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #8). Eight leaves headroom for the HTTP surface this
     * service also serves; raising it without raising the connection pool would move the queue from
     * the drain into the pool, which is a worse place for it because it is shared with the API.
     *
     * <p>1 restores the previous sequential behaviour exactly, which is the escape hatch if a partner
     * turns out to require strictly ordered delivery.
     */
    private final int concurrency;

    /**
     * Optional alerting collaborator (WBS 8.6-T24). When non-null, each drain checks the
     * total PENDING backlog and fires a P2 queue-depth alert past the threshold. Nullable
     * so the dispatcher unit test can construct it without an alert sink.
     */
    private final WebhookAlertService alertService;

    /** Backwards-compatible constructor — no queue-depth alerting, sequential delivery. */
    public WebhookDispatcher(WebhookSender sender,
                             WebhookDeliveryRepository deliveryRepository,
                             WebhookPersistenceService persistence,
                             WebhookTargetResolver targetResolver,
                             RetryPolicy retryPolicy,
                             Clock clock,
                             int batchSize) {
        this(sender, deliveryRepository, persistence, targetResolver, retryPolicy, clock, batchSize, null);
    }

    /** Backwards-compatible constructor — alerting, sequential delivery. */
    public WebhookDispatcher(WebhookSender sender,
                             WebhookDeliveryRepository deliveryRepository,
                             WebhookPersistenceService persistence,
                             WebhookTargetResolver targetResolver,
                             RetryPolicy retryPolicy,
                             Clock clock,
                             int batchSize,
                             WebhookAlertService alertService) {
        this(sender, deliveryRepository, persistence, targetResolver, retryPolicy, clock,
                batchSize, 1, alertService);
    }

    @Autowired
    public WebhookDispatcher(WebhookSender sender,
                             WebhookDeliveryRepository deliveryRepository,
                             WebhookPersistenceService persistence,
                             WebhookTargetResolver targetResolver,
                             RetryPolicy retryPolicy,
                             Clock clock,
                             @Value("${gmepay.webhook.dispatcher.batch-size:200}") int batchSize,
                             @Value("${gmepay.webhook.dispatcher.concurrency:8}") int concurrency,
                             WebhookAlertService alertService) {
        this.sender = Objects.requireNonNull(sender);
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository);
        this.persistence = Objects.requireNonNull(persistence);
        this.targetResolver = Objects.requireNonNull(targetResolver);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.clock = Objects.requireNonNull(clock);
        this.batchSize = batchSize > 0 ? batchSize : 200;
        this.concurrency = concurrency > 0 ? concurrency : 1;
        this.alertService = alertService; // optional: may be null
    }

    /**
     * Scheduled drain.
     *
     * <p><b>Cadence 5 s</b> (was 30 s; override {@code gmepay.webhook.dispatcher.interval-ms}). With
     * {@code fixedDelay} the interval is dead time <em>after</em> a drain finishes, so on a service
     * whose queue does not close it was 30 s of every cycle spent not delivering. 5 s keeps the idle
     * cost negligible (one indexed count + one indexed select against an empty result) while removing
     * most of that dead time.
     *
     * <p><b>{@link SchedulerLock}</b> makes a second replica safe: without it, two instances read the
     * same PENDING rows in the same window and both POST them, so the partner receives duplicate
     * webhooks — and a webhook is an instruction a partner acts on. {@code lockAtMostFor} is the
     * crash safety net: a holder that dies mid-drain releases nothing, so the lock must expire on its
     * own or the queue stops forever. It is sized above the worst-case drain
     * (batch-size / concurrency x read-timeout ~= 200/8 x 5 s = 125 s) with margin, because expiring
     * <em>early</em> would let a second instance start while the first is still delivering — which is
     * the duplicate this lock exists to prevent. {@code lockAtLeastFor} is deliberately zero: a fast
     * empty tick should release immediately so a backlog arriving a second later is picked up at once.
     */
    @Scheduled(
            fixedDelayString = "${gmepay.webhook.dispatcher.interval-ms:5000}",
            initialDelayString = "${gmepay.webhook.dispatcher.initial-delay-ms:5000}")
    @SchedulerLock(name = "WebhookDispatcher_drainPending",
            lockAtMostFor = "${gmepay.webhook.dispatcher.lock-at-most-for:PT10M}",
            lockAtLeastFor = "PT0S")
    public void drainPending() {
        // WBS 8.6-T24: fire a P2 queue-depth alert if the global PENDING backlog has
        // breached the threshold (deduped + suppressed inside the alert service).
        if (alertService != null) {
            long pendingTotal = deliveryRepository.countByStatus(WebhookPersistenceService.STATUS_PENDING);
            alertService.fireQueueDepthAlert(null, pendingTotal);
        }

        List<WebhookDeliveryEntity> pending = deliveryRepository.findByStatusOrderByCreatedAtAsc(
                WebhookPersistenceService.STATUS_PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        int dispatched = (concurrency <= 1 || pending.size() == 1)
                ? dispatchSequentially(pending, now)
                : dispatchConcurrently(pending, now);
        if (dispatched > 0) {
            log.info("webhook dispatcher: attempted {} of {} PENDING rows", dispatched, pending.size());
        }
    }

    private int dispatchSequentially(List<WebhookDeliveryEntity> pending, Instant now) {
        int dispatched = 0;
        for (WebhookDeliveryEntity row : pending) {
            if (dispatchGuarded(row, now)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    /**
     * Delivers the batch through a bounded pool of virtual threads, then waits for all of them.
     *
     * <p>Virtual threads because the work is a blocking socket read: 8 platform threads parked on
     * partner endpoints would be 8 OS threads doing nothing, whereas the semaphore here — not the
     * thread type — is what actually bounds concurrency. Java 21 is the toolchain the whole repo
     * targets, so this needs no new dependency and no long-lived executor to leak.
     *
     * <p>The executor is created and closed per drain, and {@code close()} on a
     * {@code newVirtualThreadPerTaskExecutor} blocks until every task finishes. That is deliberate:
     * the drain must not return while deliveries are still in flight, because {@code fixedDelay}
     * would then start the next cycle — and the ShedLock window — on top of the current one, and the
     * next select would re-read rows whose attempt is still being recorded.
     *
     * <p>Rows are independent by construction (each row is one delivery of one event to one endpoint,
     * and every path through {@link #dispatchOne} advances only its own row), so no ordering is lost
     * relative to what the previous sequential loop guaranteed — which was ordering of <em>attempts
     * started</em>, never ordering of arrivals at the partner.
     */
    private int dispatchConcurrently(List<WebhookDeliveryEntity> pending, Instant now) {
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(concurrency);
        java.util.concurrent.atomic.AtomicInteger dispatched = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (WebhookDeliveryEntity row : pending) {
                executor.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        if (dispatchGuarded(row, now)) {
                            dispatched.incrementAndGet();
                        }
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        return dispatched.get();
    }

    /** One row, with the "never let a bad row stall the drain" guarantee the loop always had. */
    private boolean dispatchGuarded(WebhookDeliveryEntity row, Instant now) {
        try {
            return dispatchOne(row, now);
        } catch (RuntimeException e) {
            // Never let one bad row stall the drain; it stays PENDING for next cycle.
            log.error("webhook dispatch error on id={} webhookId={}: {}",
                    row.getId(), row.getWebhookId(), e.getMessage(), e);
            return false;
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
            // #92: count an unresolved target as a failed attempt so a permanently
            // unresolvable row (no endpoint / secret) DLQs after the retry ceiling instead
            // of being re-picked every drain forever. A transient unresolve still resolves
            // within the backoff window; the resolver already logged the specific reason.
            int attempt = row.getAttempt() + 1;
            persistence.markAttemptFailedOrDlq(row, attempt, "target unresolved (no endpoint or signing secret)");
            return true;
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
                    // T5-4: non-null only during a secret-rotation overlap window, when the
                    // retired secret is signed with as a second header value.
                    target.get().secondarySecret(),
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
