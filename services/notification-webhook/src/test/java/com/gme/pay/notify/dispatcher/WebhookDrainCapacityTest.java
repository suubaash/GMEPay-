package com.gme.pay.notify.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gme.pay.notify.alert.WebhookBacklogMonitor;
import com.gme.pay.notify.config.ShedLockConfig;
import com.gme.pay.notify.domain.RetryPolicy;
import com.gme.pay.notify.domain.WebhookSender;
import com.gme.pay.notify.domain.WebhookSender.WebhookDeliveryResult;
import com.gme.pay.notify.dispatcher.WebhookTargetResolver.ResolvedTarget;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * T3-11 defect 5 (the drain does not keep up) and defect 3 (no distributed lock), for
 * notification-webhook.
 *
 * <p>{@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #6 put the problem as arithmetic: 200 rows delivered
 * one after another, each up to the HTTP read timeout, on a 30 s cycle. The tests below hold that
 * arithmetic to account — a batch of slow deliveries must now complete in roughly
 * {@code batch / concurrency} times the per-delivery cost, not {@code batch} times it — and separately
 * prove that the concurrency is safe to run because a second replica is locked out.
 */
class WebhookDrainCapacityTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RetryPolicy retryPolicy = new RetryPolicy();
    private final WebhookDeliveryRepository repo = mock(WebhookDeliveryRepository.class);
    private final WebhookPersistenceService persistence = mock(WebhookPersistenceService.class);
    private final WebhookTargetResolver resolver = mock(WebhookTargetResolver.class);

    // -----------------------------------------------------------------------------------------
    // Defect 5 — the drain keeps up
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a batch of slow deliveries drains concurrently, not one at a time")
    void slowDeliveriesRunConcurrentlyUpToTheConfiguredLimit() {
        int batch = 16;
        int concurrency = 8;
        // Each "delivery" parks for this long, standing in for a partner endpoint that is slow but
        // not failing — the case that used to serialise the whole batch.
        Duration perDelivery = Duration.ofMillis(200);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();

        WebhookSender sender = mock(WebhookSender.class);
        when(sender.sendWithAttempt(anyString(), anyString(), anyString(), any(), anyString(),
                any(), anyInt()))
                .thenAnswer(invocation -> {
                    int now = inFlight.incrementAndGet();
                    peakInFlight.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(perDelivery.toMillis());
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return WebhookDeliveryResult.of(200, "ok", perDelivery.toMillis());
                });

        stubBatch(batch);
        WebhookDispatcher dispatcher = new WebhookDispatcher(
                sender, repo, persistence, resolver, retryPolicy, clock, batch, concurrency, null);

        long start = System.nanoTime();
        dispatcher.drainPending();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        long sequentialMs = batch * perDelivery.toMillis();          // 3200ms — the old behaviour
        assertThat(elapsedMs)
                .as("16 x 200ms deliveries at concurrency 8 must take about 400ms, not %dms",
                        sequentialMs)
                .isLessThan(sequentialMs / 2);

        assertThat(peakInFlight.get())
                .as("concurrency must actually be used")
                .isGreaterThan(1);
        assertThat(peakInFlight.get())
                .as("...and must stay bounded: each worker holds a socket AND demand on a "
                        + "connection pool whose default size is 10, so an unbounded fan-out would "
                        + "move the queue somewhere worse")
                .isLessThanOrEqualTo(concurrency);
    }

    @Test
    @DisplayName("concurrency=1 restores strictly sequential delivery")
    void concurrencyOfOneIsStillSequential() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        WebhookSender sender = mock(WebhookSender.class);
        when(sender.sendWithAttempt(anyString(), anyString(), anyString(), any(), anyString(),
                any(), anyInt()))
                .thenAnswer(invocation -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(5);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return WebhookDeliveryResult.of(200, "ok", 5);
                });

        stubBatch(8);
        new WebhookDispatcher(sender, repo, persistence, resolver, retryPolicy, clock, 8, 1, null)
                .drainPending();

        // The escape hatch has to really be an escape hatch: if a partner ever turns out to require
        // strictly ordered delivery, setting concurrency=1 must reproduce the old behaviour exactly.
        assertThat(peak.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("one exploding delivery does not stop the rest of the batch")
    void oneBadRowDoesNotStallTheConcurrentDrain() {
        AtomicInteger delivered = new AtomicInteger();

        WebhookSender sender = mock(WebhookSender.class);
        when(sender.sendWithAttempt(anyString(), anyString(), anyString(), any(), anyString(),
                any(), anyInt()))
                .thenAnswer(invocation -> {
                    String webhookId = invocation.getArgument(0);
                    if ("evt_3".equals(webhookId)) {
                        throw new IllegalStateException("boom");
                    }
                    delivered.incrementAndGet();
                    return WebhookDeliveryResult.of(200, "ok", 1);
                });

        stubBatch(6);
        new WebhookDispatcher(sender, repo, persistence, resolver, retryPolicy, clock, 6, 4, null)
                .drainPending();

        // The sequential loop already guaranteed this; concurrency must not lose it.
        assertThat(delivered.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("the drain returns only after every in-flight delivery has finished")
    void drainWaitsForAllWorkers() throws Exception {
        CountDownLatch allStarted = new CountDownLatch(4);
        AtomicInteger completed = new AtomicInteger();

        WebhookSender sender = mock(WebhookSender.class);
        when(sender.sendWithAttempt(anyString(), anyString(), anyString(), any(), anyString(),
                any(), anyInt()))
                .thenAnswer(invocation -> {
                    allStarted.countDown();
                    Thread.sleep(50);
                    completed.incrementAndGet();
                    return WebhookDeliveryResult.of(200, "ok", 50);
                });

        stubBatch(4);
        new WebhookDispatcher(sender, repo, persistence, resolver, retryPolicy, clock, 4, 4, null)
                .drainPending();

        // Load-bearing: fixedDelay starts counting when the drain RETURNS, and the ShedLock window
        // closes then too. Returning with work still in flight would let the next cycle re-select
        // rows whose attempt is still being recorded.
        assertThat(allStarted.await(0, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.get()).isEqualTo(4);
    }

    // -----------------------------------------------------------------------------------------
    // Defect 3 — a second instance cannot double-publish
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a second instance is refused the dispatcher lock while the first holds it")
    void secondInstanceCannotDrainConcurrently() {
        String url = "jdbc:h2:mem:notifyshedlock_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        // DriverManagerDataSource because H2 is runtimeOnly and is not on the test compile classpath.
        DataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        // The full migration set, so V008 is proven to apply on top of V001-V007 and to produce the
        // table ShedLock actually expects.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        LockProvider provider = new ShedLockConfig().lockProvider(dataSource);
        LockConfiguration config = new LockConfiguration(
                Instant.now(), "WebhookDispatcher_drainPending", Duration.ofMinutes(10), Duration.ZERO);

        Optional<SimpleLock> first = provider.lock(config);
        assertThat(first).isPresent();

        // Before T3-11 there was no lock: both replicas would have selected the same PENDING rows and
        // both POSTed them, so the partner received the same webhook twice.
        assertThat(provider.lock(config))
                .as("a second dispatcher replica must be refused while the first is draining")
                .isEmpty();

        first.get().unlock();
        assertThat(provider.lock(config))
                .as("the lock must free, or the queue is permanently stopped")
                .isPresent();
    }

    @Test
    @DisplayName("both scheduled jobs in this service carry a @SchedulerLock")
    void everyScheduledJobIsLocked() {
        List<String> unlocked = new ArrayList<>();
        int scheduled = 0;
        for (Class<?> type : List.of(WebhookDispatcher.class, WebhookBacklogMonitor.class)) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getAnnotation(Scheduled.class) == null) {
                    continue;
                }
                scheduled++;
                if (method.getAnnotation(SchedulerLock.class) == null) {
                    unlocked.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(scheduled).isEqualTo(2);
        assertThat(unlocked)
                .as("an unlocked job here means either duplicate webhooks or N pages for one incident")
                .isEmpty();
    }

    @Test
    @DisplayName("the drain's lock window exceeds its own worst-case run")
    void lockWindowCoversTheWorstCaseDrain() throws Exception {
        SchedulerLock lock = WebhookDispatcher.class
                .getDeclaredMethod("drainPending").getAnnotation(SchedulerLock.class);

        // lockAtMostFor is a placeholder-bearing string; the default after the colon is what ships.
        String shipped = lock.lockAtMostFor().replaceAll("^\\$\\{[^:]+:", "").replaceAll("}$", "");
        Duration window = Duration.parse(shipped);

        // Worst case = batch-size / concurrency x per-delivery read timeout = 200/8 x 5s = 125s.
        // Expiring EARLY is the dangerous direction: it lets a second replica start while the first
        // is still delivering, which is the duplicate this lock exists to prevent.
        assertThat(window)
                .as("lockAtMostFor must exceed the worst-case drain, with margin")
                .isGreaterThan(Duration.ofSeconds(125));
    }

    // -----------------------------------------------------------------------------------------

    /** Stubs a PENDING batch of {@code n} fresh rows with a resolvable target. */
    private void stubBatch(int n) {
        List<WebhookDeliveryEntity> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            WebhookDeliveryEntity row = new WebhookDeliveryEntity();
            row.setId((long) i);
            row.setWebhookId("evt_" + i);
            row.setEventType("payment.approved");
            row.setPayload("{\"partnerId\":7}");
            row.setStatus("PENDING");
            row.setAttempt(0);
            row.setCreatedAt(NOW.minusSeconds(60));
            rows.add(row);
        }
        when(repo.findByStatusOrderByCreatedAtAsc(anyString(), any())).thenReturn(rows);
        when(repo.countByStatus(anyString())).thenReturn((long) n);
        when(resolver.resolve(any())).thenReturn(
                Optional.of(new ResolvedTarget("https://partner.example/hook", "secret", null)));
    }
}
