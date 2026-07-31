package com.gme.pay.notify.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gme.pay.notify.alert.WebhookAlertService;
import com.gme.pay.notify.domain.RetryPolicy;
import com.gme.pay.notify.domain.WebhookSender;
import com.gme.pay.notify.domain.WebhookSender.WebhookDeliveryResult;
import com.gme.pay.notify.dispatcher.WebhookTargetResolver.ResolvedTarget;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * <b>T3-11 defect 5, the design half: one partner's dead endpoint must not degrade delivery to every
 * other partner.</b>
 *
 * <p>T3-11 raised concurrency to 8 and called the coupling out rather than fixing it: rows were still
 * selected in one global {@code ORDER BY created_at} and delivered through one shared pool, so a stalled
 * partner still (a) filled the batch and (b) occupied the workers. Concurrency moved the number of
 * simultaneously-stalled deliveries needed to stall everyone from 1 to 8. This test is the one that has
 * to prove the coupling is gone rather than raised, so it is a <b>real timing test</b>: real virtual
 * threads, real semaphores, deliveries that really block. Only the database read and the HTTP send are
 * stubbed, because neither of those is what is under test.
 *
 * <p>The arithmetic it holds to account, using the shipped numbers scaled down so the test is fast:
 * the shipped worst case is a 5 s read timeout with concurrency 8 on a 5 s cycle. Here one endpoint
 * blocks for 400 ms per delivery with 64 rows queued, and a healthy partner has 4 rows. Under the old
 * design the healthy partner's rows are behind 32 stalled deliveries sharing 8 workers — about 800 ms,
 * and with the real 5 s timeout instead of 200 ms it would be 20 s, i.e. four cycles late. Under
 * per-endpoint fairness the healthy partner has its own share of the batch and its own share of the
 * workers, so it finishes in milliseconds while the stalled endpoint is still in flight.
 */
class WebhookEndpointFairnessTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final Long SLOW_PARTNER = 11L;
    private static final Long HEALTHY_PARTNER = 22L;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RetryPolicy retryPolicy = new RetryPolicy();
    private final WebhookDeliveryRepository repo = mock(WebhookDeliveryRepository.class);
    private final WebhookPersistenceService persistence = mock(WebhookPersistenceService.class);
    private final WebhookTargetResolver resolver = mock(WebhookTargetResolver.class);

    /** Per-partner delivery completion counts, for the timing assertions. */
    private final ConcurrentHashMap<Long, AtomicInteger> delivered = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> peakInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    private WebhookDispatcher dispatcher(int batchSize, int concurrency,
                                         WebhookEndpointCircuitBreaker breaker,
                                         WebhookAlertService alertService) {
        return new WebhookDispatcher(mock(WebhookSender.class), repo, persistence, resolver,
                retryPolicy, clock, batchSize, concurrency, 0, true, alertService, breaker);
    }

    // ---------------------------------------------------------------------------------------------
    // The headline: a healthy partner drains while another partner's endpoint is stalled
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a healthy partner's deliveries complete while a stalled partner is still in flight")
    void aStalledEndpointDoesNotDelayAHealthyPartner() throws Exception {
        int concurrency = 8;
        int batchSize = 200;
        Duration stall = Duration.ofMillis(200);

        List<WebhookDeliveryEntity> slowRows = rows(SLOW_PARTNER, 32);
        List<WebhookDeliveryEntity> healthyRows = rows(HEALTHY_PARTNER, 4);
        stubFairSelection(slowRows, healthyRows);

        AtomicInteger healthyFinishedAtMs = new AtomicInteger(-1);
        long start = System.nanoTime();

        WebhookSender sender = mock(WebhookSender.class);
        when(sender.sendWithAttempt(anyString(), anyString(), anyString(), any(), anyString(),
                any(), anyInt()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(2);
                    long partner = url.contains("slow") ? SLOW_PARTNER : HEALTHY_PARTNER;
                    track(partner);
                    try {
                        if (partner == SLOW_PARTNER) {
                            Thread.sleep(stall.toMillis());
                        }
                        return WebhookDeliveryResult.of(200, "ok", 1);
                    } finally {
                        untrack(partner);
                        int count = delivered.computeIfAbsent(partner, k -> new AtomicInteger())
                                .incrementAndGet();
                        if (partner == HEALTHY_PARTNER && count == healthyRows.size()) {
                            healthyFinishedAtMs.set((int) ((System.nanoTime() - start) / 1_000_000));
                        }
                    }
                });

        WebhookDispatcher dispatcher = new WebhookDispatcher(sender, repo, persistence, resolver,
                retryPolicy, clock, batchSize, concurrency, 0, true, null, null);
        dispatcher.drainPending();

        assertThat(count(HEALTHY_PARTNER)).isEqualTo(healthyRows.size());
        assertThat(count(SLOW_PARTNER)).isEqualTo(slowRows.size());

        // The whole point, in one number. Old design: the 4 healthy rows sit behind 32 x 200ms of
        // stalled work sharing 8 workers. New design: the healthy endpoint gets its own
        // share of the workers, so its rows are done within about one stall period.
        long sequentialBehindTheStall = (long) slowRows.size() * stall.toMillis() / concurrency;
        assertThat(healthyFinishedAtMs.get())
                .as("the healthy partner's 4 deliveries must finish while the stalled partner is "
                        + "still going — under the old shared-pool design they would have waited "
                        + "~%dms behind it", sequentialBehindTheStall)
                .isGreaterThanOrEqualTo(0)
                .isLessThan((int) (sequentialBehindTheStall / 2));

        assertThat(peak(SLOW_PARTNER))
                .as("a single endpoint must never hold more than its fair share of the workers: "
                        + "ceil(8 / 2) = 4")
                .isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("a single partner with work still gets the whole worker pool")
    void oneEndpointIsNotThrottledByTheFairnessCap() {
        int concurrency = 8;
        List<WebhookDeliveryEntity> only = rows(SLOW_PARTNER, 16);
        stubFairSelection(only);

        WebhookSender sender = mock(WebhookSender.class);
        when(sender.sendWithAttempt(anyString(), anyString(), anyString(), any(), anyString(),
                any(), anyInt()))
                .thenAnswer(invocation -> {
                    track(SLOW_PARTNER);
                    try {
                        Thread.sleep(100);
                        return WebhookDeliveryResult.of(200, "ok", 1);
                    } finally {
                        untrack(SLOW_PARTNER);
                    }
                });

        new WebhookDispatcher(sender, repo, persistence, resolver, retryPolicy, clock, 200,
                concurrency, 0, true, null, null).drainPending();

        // A fixed per-endpoint constant would have made this 2, i.e. a fairness mechanism causing a
        // throughput regression in the case where there is nobody to be fair to.
        assertThat(peak(SLOW_PARTNER))
                .as("with one endpoint the fair share is the whole pool")
                .isGreaterThan(4);
    }

    @Test
    @DisplayName("selection gives every endpoint a share instead of the globally-oldest rows")
    void selectionIsSharedNotGloballyOldest() {
        // The composition half: 500 old rows for the stalled partner, 3 new rows for the healthy one.
        // Under one global ORDER BY created_at with batch-size 200 the healthy rows are not merely
        // delivered late — they are never in the batch at all.
        List<WebhookDeliveryEntity> slowRows = rows(SLOW_PARTNER, 500);
        List<WebhookDeliveryEntity> healthyRows = rows(HEALTHY_PARTNER, 3);
        for (WebhookDeliveryEntity row : healthyRows) {
            row.setCreatedAt(NOW.minusSeconds(1)); // newest in the table
        }
        stubFairSelection(slowRows, healthyRows);

        List<WebhookDeliveryEntity> selected =
                dispatcher(200, 8, null, null).selectFairly();

        assertThat(selected).hasSizeLessThanOrEqualTo(200);
        assertThat(selected).extracting(WebhookDeliveryEntity::getPartnerId)
                .contains(HEALTHY_PARTNER, SLOW_PARTNER);
        assertThat(selected.stream().filter(r -> HEALTHY_PARTNER.equals(r.getPartnerId())).count())
                .as("all three of the healthy partner's rows must be selected, however deep the "
                        + "other partner's backlog is")
                .isEqualTo(3);

        // ...and the interleave means the healthy rows are near the FRONT, not behind a full share of
        // stalled deliveries. Appending shares end-to-end would be the same stall with extra steps.
        int firstHealthy = 0;
        while (firstHealthy < selected.size()
                && !HEALTHY_PARTNER.equals(selected.get(firstHealthy).getPartnerId())) {
            firstHealthy++;
        }
        assertThat(firstHealthy).as("round-robin, so a healthy row appears within the first few")
                .isLessThan(4);
    }

    @Test
    @DisplayName("pre-V009 rows with no partner_id are still selected, as one unattributed group")
    void legacyRowsWithoutAPartnerAreStillDelivered() {
        List<WebhookDeliveryEntity> legacy = rows(null, 5);
        when(repo.findDistinctPartnerIdsByStatus(anyString()))
                .thenReturn(java.util.Collections.singletonList(null));
        when(repo.findByStatusAndPartnerIdIsNullOrderByCreatedAtAsc(anyString(), any()))
                .thenReturn(legacy);

        // Invisible rows would be a far worse failure than unfair ones — a webhook the platform
        // believes it has queued and never looks at again.
        assertThat(dispatcher(200, 8, null, null).selectFairly()).hasSize(5);
    }

    // ---------------------------------------------------------------------------------------------
    // The circuit breaker
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a failing endpoint is short-circuited, and its rows keep their retry budget")
    void aFailingEndpointStopsConsumingWorkers() {
        List<WebhookDeliveryEntity> failingRows = rows(SLOW_PARTNER, 20);
        List<WebhookDeliveryEntity> healthyRows = rows(HEALTHY_PARTNER, 5);
        stubFairSelection(failingRows, healthyRows);

        AtomicInteger sendsToFailingEndpoint = new AtomicInteger();
        WebhookSender sender = mock(WebhookSender.class);
        when(sender.sendWithAttempt(anyString(), anyString(), anyString(), any(), anyString(),
                any(), anyInt()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(2);
                    if (url.contains("slow")) {
                        sendsToFailingEndpoint.incrementAndGet();
                        return WebhookDeliveryResult.of(503, "unavailable", 1);
                    }
                    return WebhookDeliveryResult.of(200, "ok", 1);
                });

        WebhookEndpointCircuitBreaker breaker =
                new WebhookEndpointCircuitBreaker(clock, 5, Duration.ofMinutes(1));
        WebhookAlertService alertService = mock(WebhookAlertService.class);

        new WebhookDispatcher(sender, repo, persistence, resolver, retryPolicy, clock, 200, 1, 0,
                true, alertService, breaker).drainPending();

        assertThat(breaker.isOpen(SLOW_PARTNER))
                .as("five consecutive 503s must trip it")
                .isTrue();
        assertThat(breaker.isOpen(HEALTHY_PARTNER))
                .as("...and must not trip anyone else — that is the whole point of per-endpoint state")
                .isFalse();
        assertThat(sendsToFailingEndpoint.get())
                .as("only the failures up to the threshold reach the dead endpoint; the remaining 15 "
                        + "rows are skipped without opening a socket")
                .isEqualTo(5);

        // Rows skipped by an open breaker are NOT failed attempts: markAttemptFailedOrDlq is called
        // for the five real attempts only, so a one-minute outage cannot spend a ten-attempt budget.
        org.mockito.Mockito.verify(persistence, org.mockito.Mockito.times(5))
                .markAttemptFailedOrDlq(any(), anyInt(), anyString());

        // Suppressing deliveries silently would trade one invisible failure for another.
        org.mockito.Mockito.verify(alertService, org.mockito.Mockito.times(1))
                .fireEndpointCircuitOpenAlert(eq(SLOW_PARTNER), eq(5), eq(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("the drain still reaches every healthy row in the batch while one endpoint is open")
    void healthyRowsAreAllDeliveredDespiteAnOpenBreaker() {
        List<WebhookDeliveryEntity> failingRows = rows(SLOW_PARTNER, 50);
        List<WebhookDeliveryEntity> healthyRows = rows(HEALTHY_PARTNER, 12);
        stubFairSelection(failingRows, healthyRows);

        WebhookSender sender = mock(WebhookSender.class);
        when(sender.sendWithAttempt(anyString(), anyString(), anyString(), any(), anyString(),
                any(), anyInt()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(2);
                    long partner = url.contains("slow") ? SLOW_PARTNER : HEALTHY_PARTNER;
                    delivered.computeIfAbsent(partner, k -> new AtomicInteger()).incrementAndGet();
                    if (partner == SLOW_PARTNER) {
                        // A hung endpoint: accept, then answer nothing until the read timeout.
                        Thread.sleep(300);
                        return WebhookDeliveryResult.of(504, "gateway timeout", 300);
                    }
                    return WebhookDeliveryResult.of(200, "ok", 1);
                });

        new WebhookDispatcher(sender, repo, persistence, resolver, retryPolicy, clock, 200, 8, 0,
                true, null, new WebhookEndpointCircuitBreaker(clock, 5, Duration.ofMinutes(1)))
                .drainPending();

        assertThat(count(HEALTHY_PARTNER))
                .as("every healthy row in the batch must be delivered in the SAME cycle, not the next "
                        + "one — this is what 'a partner whose endpoint is down must not degrade "
                        + "delivery to every other partner' means in practice")
                .isEqualTo(12);
        assertThat(count(SLOW_PARTNER))
                .as("and the dead endpoint stops being retried inside this cycle once it trips")
                .isLessThan(50);
    }

    // ---------------------------------------------------------------------------------------------
    // The shipped configuration
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the shipped drain interval really is 1 s, and fairness really is on")
    void shippedConfigurationMatchesTheDesign() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));

        // T3-11 shortened the @Scheduled default to 5000 but this file still shipped 30000, which
        // overrode it — so the cycle change was not in effect in any deployment. Pinned here.
        // Then 5000 -> 1000: with fixedDelay the interval is dead time after the drain returns, and at
        // any realistic partner latency it was the dominant term in the cycle rather than a minor one.
        // The capacity arithmetic that depends on it is asserted in WebhookDrainThroughputTest.
        assertThat(defaulted(shipped.getProperty("gmepay.webhook.dispatcher.interval-ms")))
                .isEqualTo("1000");
        assertThat(defaulted(shipped.getProperty("gmepay.webhook.dispatcher.fair-selection")))
                .isEqualTo("true");
        assertThat(defaulted(shipped.getProperty("gmepay.webhook.dispatcher.breaker.enabled")))
                .as("a fairness mechanism that has to be switched on is one nobody switched on")
                .isEqualTo("true");
        assertThat(defaulted(
                shipped.getProperty("gmepay.webhook.dispatcher.max-in-flight-per-endpoint")))
                .as("0 = derive the fair share; a constant would throttle a single-partner deployment")
                .isEqualTo("0");
    }

    /** Reads the default out of a {@code ${ENV_VAR:default}} placeholder. */
    private static String defaulted(String raw) {
        assertThat(raw).isNotNull();
        return raw.replaceAll("^\\$\\{[^:]+:", "").replaceAll("}$", "");
    }

    // ---------------------------------------------------------------------------------------------

    @SafeVarargs
    private void stubFairSelection(List<WebhookDeliveryEntity>... shares) {
        List<Long> partners = new ArrayList<>();
        for (List<WebhookDeliveryEntity> share : shares) {
            Long partnerId = share.get(0).getPartnerId();
            partners.add(partnerId);
            if (partnerId == null) {
                when(repo.findByStatusAndPartnerIdIsNullOrderByCreatedAtAsc(anyString(), any()))
                        .thenAnswer(invocation -> page(share, invocation.getArgument(1)));
            } else {
                when(repo.findByStatusAndPartnerIdOrderByCreatedAtAsc(
                        anyString(), eq(partnerId), any()))
                        .thenAnswer(invocation -> page(share, invocation.getArgument(2)));
            }
        }
        when(repo.findDistinctPartnerIdsByStatus(anyString())).thenReturn(partners);
        when(repo.countByStatus(anyString())).thenReturn(0L);
        when(resolver.resolve(any())).thenAnswer(invocation -> {
            WebhookDeliveryEntity row = invocation.getArgument(0);
            String host = SLOW_PARTNER.equals(row.getPartnerId()) ? "slow" : "healthy";
            return Optional.of(new ResolvedTarget("https://" + host + ".example/hook", "secret", null));
        });
    }

    /** Applies the repository's own page limit, so the fair share is really enforced by the query. */
    private static List<WebhookDeliveryEntity> page(List<WebhookDeliveryEntity> rows,
                                                    org.springframework.data.domain.Pageable pageable) {
        int size = pageable.getPageSize();
        return rows.size() <= size ? rows : new ArrayList<>(rows.subList(0, size));
    }

    private List<WebhookDeliveryEntity> rows(Long partnerId, int count) {
        List<WebhookDeliveryEntity> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            WebhookDeliveryEntity row = new WebhookDeliveryEntity();
            row.setId((partnerId == null ? 900_000L : partnerId * 1000) + i);
            row.setWebhookId("evt_" + (partnerId == null ? "legacy" : partnerId) + "_" + i);
            row.setEventType("payment.approved");
            row.setPayload("{\"partnerId\":" + partnerId + "}");
            row.setPartnerId(partnerId);
            row.setStatus(WebhookPersistenceService.STATUS_PENDING);
            row.setAttempt(0);
            row.setCreatedAt(NOW.minusSeconds(3600));
            rows.add(row);
        }
        return rows;
    }

    private void track(long partner) {
        int now = inFlight.computeIfAbsent(partner, k -> new AtomicInteger()).incrementAndGet();
        peakInFlight.computeIfAbsent(partner, k -> new AtomicInteger())
                .accumulateAndGet(now, Math::max);
    }

    private void untrack(long partner) {
        inFlight.get(partner).decrementAndGet();
    }

    private int count(long partner) {
        AtomicInteger counter = delivered.get(partner);
        return counter == null ? 0 : counter.get();
    }

    private int peak(long partner) {
        AtomicInteger counter = peakInFlight.get(partner);
        return counter == null ? 0 : counter.get();
    }
}
