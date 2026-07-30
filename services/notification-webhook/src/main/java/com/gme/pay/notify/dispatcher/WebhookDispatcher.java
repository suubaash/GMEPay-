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
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 * <h2>Per-endpoint fairness — the design defect T3-11 left open</h2>
 *
 * <p>Raising concurrency to 8 raised the number of simultaneously-stalled deliveries needed to stall
 * everyone from 1 to 8. It did not decouple partners, because one partner's dead endpoint could still
 * (a) fill the batch, since selection was a single global {@code ORDER BY created_at}, and (b) occupy
 * the shared workers, since every row competed for the same eight permits. Both halves are now closed,
 * and they need separate mechanisms because they are separate failures:
 *
 * <ol>
 *   <li><b>Fair selection</b> ({@link #selectFairly()}). Flyway V009 put the partner on the delivery
 *       row, so the drain asks each endpoint that has work for its own share of {@code batch-size}
 *       instead of taking the globally-oldest rows. A partner with a 10 000-row backlog can no longer
 *       leave a healthy partner's three rows unselected. Shares are interleaved round-robin, so the
 *       <em>order</em> is fair too and a healthy partner is not queued behind a full share of stalled
 *       deliveries.</li>
 *   <li><b>A per-endpoint in-flight cap.</b> Each endpoint may hold at most its fair share of the
 *       workers ({@code ceil(concurrency / endpoints-with-work)}, or an explicit
 *       {@code max-in-flight-per-endpoint}). This is what bounds a <em>slow but succeeding</em>
 *       endpoint, which no failure-counting breaker can catch: with one endpoint it is the whole pool
 *       (so nothing regresses for a single-partner deployment), with two it is half each.</li>
 *   <li><b>A per-endpoint circuit breaker</b> ({@link WebhookEndpointCircuitBreaker}). After N
 *       consecutive failures an endpoint's rows are skipped entirely for a cooldown, so a
 *       <em>failing</em> endpoint spends no worker time at all, and a half-open probe re-tests it. A
 *       skipped row is not a failed attempt, so an outage does not burn the retry budget.</li>
 * </ol>
 *
 * <p><b>Nothing parallel to the existing pipeline was added.</b> Terminal failure is still
 * {@code webhook_dlq} via {@link RetryPolicy}; endpoint identity is still the {@code (partnerId,
 * environment)} key T5-4's per-endpoint signing established; the alerts are still
 * {@code WebhookAlertService}'s {@code alert_event} rows. The one new alert type,
 * {@code WEBHOOK_ENDPOINT_CIRCUIT_OPEN}, exists because suppressing deliveries silently would trade
 * one invisible failure for another.
 *
 * <p><b>What this still does not do:</b> ordering across a partner's own rows is preserved only as
 * "attempts started oldest-first", exactly as before; and fairness is per partner, not per event type,
 * so one partner's slow endpoint still delays that partner's other events. Both are properties of the
 * previous design that this change deliberately did not alter.
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

    /**
     * Explicit per-endpoint in-flight cap, or {@code 0} to derive the fair share
     * {@code ceil(concurrency / endpoints-with-work)}.
     *
     * <p>Derived is the default and is the better answer for the same reason a fixed number is
     * tempting: a constant of, say, 2 would throttle a single-partner deployment from 8 workers to 2 —
     * a fairness mechanism causing a throughput regression in the case where there is nobody to be fair
     * to. The derived share is the whole pool when one endpoint has work and shrinks only as other
     * endpoints actually need it.
     */
    private final int maxInFlightPerEndpoint;

    /**
     * Per-endpoint fair selection. Configurable so the previous global-FIFO behaviour is still
     * reachable ({@code gmepay.webhook.dispatcher.fair-selection=false}) — an escape hatch, not a
     * recommendation, kept because a selection change is the kind of thing an operator may need to
     * revert at 3am without a deploy.
     */
    private final boolean fairSelection;

    /**
     * Per-endpoint circuit breaker; {@code null} in the legacy constructors used by tests that predate
     * it, in which case every attempt is allowed and behaviour is exactly as before.
     */
    private final WebhookEndpointCircuitBreaker breaker;

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

    /** Backwards-compatible constructor — concurrency, no per-endpoint fairness. */
    public WebhookDispatcher(WebhookSender sender,
                             WebhookDeliveryRepository deliveryRepository,
                             WebhookPersistenceService persistence,
                             WebhookTargetResolver targetResolver,
                             RetryPolicy retryPolicy,
                             Clock clock,
                             int batchSize,
                             int concurrency,
                             WebhookAlertService alertService) {
        // fair-selection OFF and no breaker: these constructors exist for callers written against the
        // pre-fairness behaviour, and a constructor kept for backwards compatibility that quietly
        // changed selection would not be backwards compatible.
        this(sender, deliveryRepository, persistence, targetResolver, retryPolicy, clock, batchSize,
                concurrency, 0, false, alertService, null);
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
                             @Value("${gmepay.webhook.dispatcher.max-in-flight-per-endpoint:0}")
                             int maxInFlightPerEndpoint,
                             @Value("${gmepay.webhook.dispatcher.fair-selection:true}")
                             boolean fairSelection,
                             WebhookAlertService alertService,
                             WebhookEndpointCircuitBreaker breaker) {
        this.sender = Objects.requireNonNull(sender);
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository);
        this.persistence = Objects.requireNonNull(persistence);
        this.targetResolver = Objects.requireNonNull(targetResolver);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.clock = Objects.requireNonNull(clock);
        this.batchSize = batchSize > 0 ? batchSize : 200;
        this.concurrency = concurrency > 0 ? concurrency : 1;
        this.maxInFlightPerEndpoint = Math.max(0, maxInFlightPerEndpoint);
        this.fairSelection = fairSelection;
        this.alertService = alertService; // optional: may be null
        this.breaker = breaker;           // optional: may be null (legacy constructors)
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

        List<WebhookDeliveryEntity> pending = selectFairly();
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

    /**
     * Selects the batch <b>per endpoint</b> instead of taking the globally-oldest PENDING rows.
     *
     * <p>The old query was {@code WHERE status='PENDING' ORDER BY created_at LIMIT batch-size}. Its
     * defect was not throughput, it was <em>composition</em>: a partner with 10 000 queued rows filled
     * every page, so a healthy partner's three rows were not merely delivered late, they were never
     * selected. No amount of concurrency fixes that, because the rows are not in the batch to begin
     * with.
     *
     * <p>Instead: ask which endpoints have work (bounded by the number of registered partners, not by
     * the backlog — one indexed DISTINCT), give each an equal share of {@code batch-size}, and
     * interleave the shares round-robin. The interleave matters as much as the share does: appending
     * one partner's whole share before another's would put a healthy partner's rows behind a full share
     * of stalled deliveries, which is the same stall with extra steps.
     *
     * <p>Costs one query per endpoint-with-work rather than one overall. That is the price of fairness
     * and it is bounded by the partner count; every one of them is an indexed, paged read on
     * {@code (status, partner_id, created_at)}.
     *
     * <p>A single endpoint with work degenerates to exactly the old behaviour (one query, its whole
     * share = {@code batch-size}), so nothing changes for a single-partner deployment.
     *
     * <p>{@code null} is a real key here: rows written before V009 carry no {@code partner_id} and are
     * treated as one unattributed group rather than dropped from selection — invisible rows would be a
     * far worse failure than unfair ones.
     */
    List<WebhookDeliveryEntity> selectFairly() {
        String pendingStatus = WebhookPersistenceService.STATUS_PENDING;
        if (!fairSelection) {
            return deliveryRepository.findByStatusOrderByCreatedAtAsc(
                    pendingStatus, PageRequest.of(0, batchSize));
        }

        List<Long> endpoints = deliveryRepository.findDistinctPartnerIdsByStatus(pendingStatus);
        if (endpoints == null || endpoints.isEmpty()) {
            return List.of();
        }

        int share = Math.max(1, (int) Math.ceil((double) batchSize / endpoints.size()));
        Pageable page = PageRequest.of(0, Math.min(share, batchSize));
        List<List<WebhookDeliveryEntity>> shares = new ArrayList<>(endpoints.size());
        for (Long partnerId : endpoints) {
            List<WebhookDeliveryEntity> rows = partnerId == null
                    ? deliveryRepository.findByStatusAndPartnerIdIsNullOrderByCreatedAtAsc(
                            pendingStatus, page)
                    : deliveryRepository.findByStatusAndPartnerIdOrderByCreatedAtAsc(
                            pendingStatus, partnerId, page);
            if (rows != null && !rows.isEmpty()) {
                shares.add(rows);
            }
        }
        return interleave(shares, batchSize);
    }

    /** Round-robin merge of per-endpoint shares, capped at {@code limit}. */
    private static List<WebhookDeliveryEntity> interleave(List<List<WebhookDeliveryEntity>> shares,
                                                          int limit) {
        List<WebhookDeliveryEntity> merged = new ArrayList<>();
        int deepest = shares.stream().mapToInt(List::size).max().orElse(0);
        for (int index = 0; index < deepest && merged.size() < limit; index++) {
            for (List<WebhookDeliveryEntity> share : shares) {
                if (index < share.size() && merged.size() < limit) {
                    merged.add(share.get(index));
                }
            }
        }
        return merged;
    }

    /**
     * The per-endpoint in-flight cap for this batch.
     *
     * <p>Derived by default as {@code ceil(concurrency / endpoints-with-work)}: one endpoint gets the
     * whole pool, two get half each, eight get one each. That is what makes this a fairness cap rather
     * than a throughput cut — a constant would throttle a single-partner deployment for the benefit of
     * partners that do not exist.
     */
    private int perEndpointCap(int endpointsWithWork) {
        if (maxInFlightPerEndpoint > 0) {
            return Math.min(maxInFlightPerEndpoint, concurrency);
        }
        int endpoints = Math.max(1, endpointsWithWork);
        return Math.max(1, (int) Math.ceil((double) concurrency / endpoints));
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

        // Per-endpoint permits: this is what stops a SLOW-but-succeeding endpoint from occupying the
        // whole pool. A failure-counting breaker cannot catch that case — every delivery eventually
        // succeeds, it just takes 4.9 s each — so the cap has to exist independently of it.
        java.util.Map<Long, java.util.concurrent.Semaphore> endpointPermits =
                new java.util.concurrent.ConcurrentHashMap<>();
        long distinctEndpoints = pending.stream().map(WebhookDispatcher::endpointKey).distinct().count();
        int cap = perEndpointCap((int) distinctEndpoints);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (WebhookDeliveryEntity row : pending) {
                java.util.concurrent.Semaphore endpoint = endpointPermits.computeIfAbsent(
                        endpointKey(row), key -> new java.util.concurrent.Semaphore(cap));
                executor.submit(() -> {
                    // Endpoint permit FIRST, then the global one. Always this order, so there is no
                    // cycle: a task can hold an endpoint permit while waiting for the global one, and
                    // that is precisely the queueing we want — the wait happens on the endpoint's own
                    // share, not on capacity another partner could be using.
                    endpoint.acquireUninterruptibly();
                    try {
                        permits.acquireUninterruptibly();
                        try {
                            if (dispatchGuarded(row, now)) {
                                dispatched.incrementAndGet();
                            }
                        } finally {
                            permits.release();
                        }
                    } finally {
                        endpoint.release();
                    }
                });
            }
        }
        return dispatched.get();
    }

    /**
     * The fairness key for one row: its partner, falling back to the payload for pre-V009 rows and to a
     * single sentinel for rows with no partner at all.
     *
     * <p>Same key the circuit breaker, the target resolver and the DLQ alert use, deliberately — an
     * endpoint that is fair-shared under one identity and short-circuited under another is two half
     * mechanisms.
     */
    private static Long endpointKey(WebhookDeliveryEntity row) {
        Long fromPayload = com.gme.pay.notify.domain.WebhookPayloads.partnerId(row.getPayload());
        if (fromPayload != null) {
            return fromPayload;
        }
        // V009's column is the fallback, not the primary: it exists so SQL can page fairly, while the
        // event itself stays the authority on whose endpoint this is — the same order the resolver uses,
        // so a row can never be capped and short-circuited under one identity and delivered under
        // another.
        Long fromColumn = row.getPartnerId();
        return fromColumn != null ? fromColumn : WebhookEndpointCircuitBreaker.UNATTRIBUTED;
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

        // Per-endpoint circuit breaker, checked AFTER the backoff gate so an open breaker never
        // consumes the half-open probe on a row that was not due anyway.
        //
        // A skipped row is NOT a failed attempt: its attempt counter and last_attempted_at are
        // untouched, so a partner outage does not burn the retry budget that exists to absorb
        // transient failures. The consequence is stated rather than hidden — DLQ promotion for a dead
        // endpoint becomes slower, driven by the half-open probes, not impossible. The growing PENDING
        // backlog remains visible through the existing queue-depth / backlog alerts, and opening the
        // breaker raises its own alert.
        Long endpoint = endpointKey(row);
        if (breaker != null && !breaker.allowAttempt(endpoint)) {
            log.debug("webhook delivery skipped: endpoint circuit open for partnerId={} (webhookId={})",
                    endpoint, row.getWebhookId());
            return false;
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
            // A non-HTTPS endpoint is a hard config error, not a transient failure — and deliberately
            // NOT a circuit-breaker failure: no socket was opened, so it costs no worker time, and
            // tripping a breaker on it would short-circuit an endpoint whose problem is its
            // registration rather than its availability.
            persistence.markAttemptFailedOrDlq(row, attempt, "non-HTTPS endpoint: " + e.getMessage());
            return true;
        }

        if (result.success()) {
            recordEndpointSuccess(endpoint);
            persistence.markDelivered(row, attempt);
            log.debug("webhook delivered: webhookId={} attempt={} status={}",
                    row.getWebhookId(), attempt, result.httpStatus());
        } else {
            recordEndpointFailure(endpoint, "HTTP " + result.httpStatus());
            persistence.markAttemptFailedOrDlq(row, attempt, result.responseBody());
            log.debug("webhook attempt failed: webhookId={} attempt={} status={}",
                    row.getWebhookId(), attempt, result.httpStatus());
        }
        return true;
    }

    private void recordEndpointSuccess(Long endpoint) {
        if (breaker != null) {
            breaker.recordSuccess(endpoint);
        }
    }

    /**
     * Feeds one failed delivery to the breaker and raises the alert on the OPEN transition only.
     *
     * <p>Once per transition, not once per failure: a 200-row batch against a dead endpoint would
     * otherwise write 200 alert rows for one incident, which is the alert-storm the queue-depth alert
     * already has a dedup window for. The alert service dedups per partner as well, so the two guards
     * are belt and braces.
     */
    private void recordEndpointFailure(Long endpoint, String reason) {
        if (breaker == null) {
            return;
        }
        boolean opened = breaker.recordFailure(endpoint, reason);
        if (opened && alertService != null) {
            Long partnerId = (endpoint != null && endpoint == WebhookEndpointCircuitBreaker.UNATTRIBUTED)
                    ? null : endpoint;
            alertService.fireEndpointCircuitOpenAlert(
                    partnerId, breaker.failureThreshold(), breaker.openDuration());
        }
    }
}
