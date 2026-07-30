package com.gme.pay.notify.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-endpoint circuit breaker for webhook delivery — the half of T3-11 defect 5 that concurrency did
 * not fix.
 *
 * <h2>The coupling this removes</h2>
 *
 * <p>Every delivery worker is shared across all partners. A partner whose endpoint accepts the
 * connection and then never answers holds a worker for the full read timeout, every time one of its
 * rows is picked up. With one worker that stalled everyone at the first bad row; with eight it takes
 * eight, which is a higher threshold for the same failure rather than a different failure. Fair
 * selection (see {@link WebhookDispatcher}) stops one partner monopolising the <em>batch</em>; this
 * stops a <em>failing</em> partner spending shared workers on deliveries that are already known not to
 * work.
 *
 * <p>The state is per {@code partnerId}, which is the endpoint's identity here: T5-4 made the signing
 * secret per-endpoint, and {@code DefaultWebhookTargetResolver} resolves one active endpoint per
 * {@code (partnerId, environment)} with the environment fixed per deployment. So "the partner" and "the
 * endpoint" are the same key, and using the partner id keeps this consistent with how the resolver, the
 * signing work and the DLQ alert already attribute a delivery.
 *
 * <h2>Why this is not resilience4j</h2>
 *
 * <p>payment-executor uses resilience4j for its outbound scheme calls, and the obvious question is why
 * this is not that. Two reasons, both about what the drain needs that a call-wrapping breaker does not
 * give: the decision here has to be readable <b>before</b> a row is handed to a worker (the entire
 * point is not to spend the worker), and it has to be keyed on a value read from a database row rather
 * than on a method. Wrapping the send call in a resilience4j breaker would still consume a worker per
 * short-circuited call and would still need this same registry to decide the key. This class is ~100
 * lines of exactly the state machine needed, with no new dependency on notification-webhook's
 * classpath.
 *
 * <h2>Relationship to the retry model and {@code webhook_dlq}</h2>
 *
 * <p>A skipped row is <b>not</b> a failed attempt: its {@code attempt} counter and
 * {@code last_attempted_at} are untouched, so an outage does not burn the retry budget that exists to
 * absorb transient failures. That deliberately makes DLQ promotion <em>slower</em> for a dead endpoint,
 * not impossible:
 *
 * <ul>
 *   <li>the open window is short ({@code open-duration}, default 1 minute), after which one row is let
 *       through as a half-open probe. That probe is a real attempt and is recorded as one, so rows keep
 *       walking through {@link com.gme.pay.notify.domain.RetryPolicy}'s ten-attempt budget toward
 *       {@code webhook_dlq} — the existing terminal state, unchanged and not duplicated;</li>
 *   <li>the growing PENDING backlog is what the existing {@code WEBHOOK_QUEUE_DEPTH} /
 *       {@code WebhookBacklogMonitor} alerts are for, so a partner that stays down is visible without
 *       this class inventing its own escalation;</li>
 *   <li>opening the breaker raises {@code WEBHOOK_ENDPOINT_CIRCUIT_OPEN} once (dedup-suppressed),
 *       because suppressing delivery attempts silently would be trading one invisible failure for
 *       another.</li>
 * </ul>
 *
 * <p>State is per-JVM and in memory. That is correct rather than a shortcut: the drain is
 * {@code @SchedulerLock}ed, so exactly one instance is delivering at a time, and the thing being
 * tracked — "are this endpoint's last few attempts failing?" — is an observation of the current drain,
 * not a fact that needs to outlive it. A restart re-probes immediately, which is the safe direction.
 */
@Component
public class WebhookEndpointCircuitBreaker {

    /** The key used for a delivery whose payload carries no partner id. */
    static final long UNATTRIBUTED = -1L;

    private static final Logger log = LoggerFactory.getLogger(WebhookEndpointCircuitBreaker.class);

    private final Clock clock;
    private final boolean enabled;
    private final int failureThreshold;
    private final Duration openDuration;

    private final Map<Long, EndpointState> states = new ConcurrentHashMap<>();

    public WebhookEndpointCircuitBreaker(
            Clock clock,
            @Value("${gmepay.webhook.dispatcher.breaker.enabled:true}") boolean enabled,
            @Value("${gmepay.webhook.dispatcher.breaker.failure-threshold:5}") int failureThreshold,
            @Value("${gmepay.webhook.dispatcher.breaker.open-duration:PT1M}") Duration openDuration) {
        this.clock = Objects.requireNonNull(clock);
        this.enabled = enabled;
        // Below 2 a single transient 500 would isolate a healthy partner, so the floor is 2.
        this.failureThreshold = Math.max(2, failureThreshold);
        this.openDuration = (openDuration == null || openDuration.isNegative() || openDuration.isZero())
                ? Duration.ofMinutes(1) : openDuration;
    }

    /** Test/diagnostic constructor with the shipped defaults. */
    WebhookEndpointCircuitBreaker(Clock clock, int failureThreshold, Duration openDuration) {
        this(clock, true, failureThreshold, openDuration);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Consecutive failures that trip an endpoint (floor 2). */
    public int failureThreshold() {
        return failureThreshold;
    }

    /** How long an open endpoint's rows are skipped before a half-open probe. */
    public Duration openDuration() {
        return openDuration;
    }

    /**
     * @return {@code true} if a delivery to this endpoint may be attempted now. When the breaker is
     *         open this returns {@code false} without touching the row, and returns {@code true} for
     *         exactly ONE caller once the open window has elapsed (the half-open probe).
     */
    public boolean allowAttempt(Long partnerId) {
        if (!enabled) {
            return true;
        }
        EndpointState state = states.get(key(partnerId));
        return state == null || state.allowAttempt(Instant.now(clock), openDuration);
    }

    /** Records a delivered webhook: closes the breaker and forgets the failure history. */
    public void recordSuccess(Long partnerId) {
        if (!enabled) {
            return;
        }
        EndpointState state = states.get(key(partnerId));
        if (state != null && state.recordSuccess()) {
            log.info("webhook endpoint circuit CLOSED for partnerId={} after a successful delivery",
                    partnerId);
        }
    }

    /**
     * Records a failed delivery attempt.
     *
     * @return {@code true} if this failure is the one that opened the breaker (so the caller can raise
     *         the alert exactly once per transition rather than once per failure)
     */
    public boolean recordFailure(Long partnerId, String reason) {
        if (!enabled) {
            return false;
        }
        EndpointState state = states.computeIfAbsent(key(partnerId), k -> new EndpointState());
        boolean opened = state.recordFailure(Instant.now(clock), failureThreshold, openDuration);
        if (opened) {
            log.warn("webhook endpoint circuit OPEN for partnerId={} after {} consecutive failed "
                            + "deliveries (last: {}); its rows are skipped for {} so other partners' "
                            + "deliveries keep the workers. Rows stay PENDING with their attempt "
                            + "counters untouched.",
                    partnerId, failureThreshold, reason, openDuration);
        }
        return opened;
    }

    /** Diagnostic: is this endpoint currently short-circuited (tripped and inside its open window)? */
    public boolean isOpen(Long partnerId) {
        EndpointState state = states.get(key(partnerId));
        return enabled && state != null && state.isOpen(Instant.now(clock), openDuration);
    }

    /** Number of endpoints currently short-circuited — for logs and the fairness test. */
    public long openEndpointCount() {
        Instant now = Instant.now(clock);
        return enabled
                ? states.values().stream().filter(s -> s.isOpen(now, openDuration)).count()
                : 0L;
    }

    private static long key(Long partnerId) {
        return partnerId == null ? UNATTRIBUTED : partnerId;
    }

    /**
     * One endpoint's state. Synchronised rather than lock-free: it is touched once per delivery, on
     * the order of tens of times a second at the very most, and the half-open rule ("exactly one probe
     * gets through") is a compare-and-set across three fields that is far easier to get right under a
     * monitor than with atomics.
     */
    private static final class EndpointState {

        private int consecutiveFailures;
        private Instant openedAt;
        private boolean probeInFlight;

        synchronized boolean allowAttempt(Instant now, Duration openDuration) {
            if (openedAt == null) {
                return true;
            }
            if (now.isBefore(openedAt.plus(openDuration))) {
                return false;
            }
            if (probeInFlight) {
                // Half-open: one probe at a time. Letting the whole batch through the moment the
                // window elapsed would re-stall every worker on the same dead endpoint.
                return false;
            }
            probeInFlight = true;
            return true;
        }

        /** @return true if this closed a previously-open breaker */
        synchronized boolean recordSuccess() {
            boolean wasOpen = openedAt != null;
            consecutiveFailures = 0;
            openedAt = null;
            probeInFlight = false;
            return wasOpen;
        }

        /** @return true if this failure opened the breaker (a transition, not a repeat) */
        synchronized boolean recordFailure(Instant now, int threshold, Duration openDuration) {
            probeInFlight = false;
            consecutiveFailures++;
            if (openedAt != null) {
                // A failed half-open probe re-arms the window from now, so the endpoint gets one
                // probe per open-duration for as long as it stays broken.
                openedAt = now;
                return false;
            }
            if (consecutiveFailures >= threshold) {
                openedAt = now;
                return true;
            }
            return false;
        }

        /** Tripped AND still inside its window, i.e. currently refusing attempts. */
        synchronized boolean isOpen(Instant now, Duration openDuration) {
            return openedAt != null && now.isBefore(openedAt.plus(openDuration));
        }
    }
}
