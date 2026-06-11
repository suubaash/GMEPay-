package com.gme.pay.txn.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Docker-backed IT for the Redis-backed {@link IdempotencyStore} (ticket 17.3-G02).
 *
 * <p>Boots the full Spring context with {@code spring.data.redis.host}/{@code port} pointing
 * at a Testcontainers {@code redis:7} container. Setting {@code spring.data.redis.host}
 * activates the {@code @ConditionalOnProperty} Redis bean in {@link IdempotencyConfig}, so
 * the {@code @Primary} {@link RedisIdempotencyStore} wins the by-type injection over the
 * in-memory fallback.
 *
 * <p>Verified contract (port: {@link IdempotencyStore}):
 * <ol>
 *   <li><b>First write wins.</b> {@code putIfAbsent("k", snapshotA)} returns
 *       {@code Optional.empty()} (won). A second call with a <em>different</em> snapshot
 *       returns the original {@code snapshotA} (lost). {@code get("k")} also yields
 *       {@code snapshotA}.</li>
 *   <li><b>24h TTL.</b> The Redis key {@code idem:&lt;key&gt;} has a TTL within ~24h of
 *       {@link IdempotencyConfig#IDEMPOTENCY_TTL}, asserted at the Redis level.</li>
 *   <li><b>SETNX concurrency.</b> 10 threads racing on the same key produce exactly one
 *       winner; every loser observes the winner's snapshot.</li>
 * </ol>
 *
 * <p>{@code @Tag("docker")}: excluded from the local {@code test} task (no Docker on this
 * Windows host); CI runs it via {@code integrationTest}. {@code disabledWithoutDocker = true}
 * self-skips defensively if no Docker engine is reachable.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class IdempotencyRedisStoreIT {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        // Setting these activates the @ConditionalOnProperty("spring.data.redis.host") bean
        // in IdempotencyConfig — the @Primary RedisIdempotencyStore. Without it, the
        // in-memory fallback wins and this IT is meaningless.
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("by-type @Primary injection resolves to the Redis-backed store, not the in-memory fallback")
    void primaryStoreIsRedisBacked() {
        assertThat(store)
                .as("spring.data.redis.host is set, so the @Primary RedisIdempotencyStore must win")
                .isInstanceOf(RedisIdempotencyStore.class);
    }

    @Test
    @DisplayName("first putIfAbsent wins; second call with a different snapshot loses and replays the winner")
    void firstWriteWinsAndReplaysOnDuplicate() {
        String key = "first-write-wins-" + UUID.randomUUID();
        String winnerSnapshot = "{\"txnRef\":\"TXN-1\",\"status\":\"APPROVED\"}";
        String loserAttempt = "{\"txnRef\":\"TXN-1\",\"status\":\"FAILED\"}";

        Optional<String> firstResult = store.putIfAbsent(key, winnerSnapshot);
        assertThat(firstResult)
                .as("first putIfAbsent must win (Optional.empty() == stored)")
                .isEmpty();

        Optional<String> secondResult = store.putIfAbsent(key, loserAttempt);
        assertThat(secondResult)
                .as("second putIfAbsent must lose and replay the winner's snapshot byte-for-byte")
                .contains(winnerSnapshot);

        assertThat(store.get(key))
                .as("get() must return the winner's snapshot")
                .contains(winnerSnapshot);

        // Sanity at the Redis level: namespaced and equal to winnerSnapshot.
        assertThat(redis.opsForValue().get(RedisIdempotencyStore.KEY_PREFIX + key))
                .isEqualTo(winnerSnapshot);
    }

    @Test
    @DisplayName("stored key carries the documented 24h TTL (within tolerance) at the Redis level")
    void storedKeyHasTwentyFourHourTtl() {
        String key = "ttl-key-" + UUID.randomUUID();
        store.putIfAbsent(key, "snapshot");

        Long ttlSeconds = redis.getExpire(RedisIdempotencyStore.KEY_PREFIX + key, TimeUnit.SECONDS);
        assertThat(ttlSeconds)
                .as("getExpire must return a positive remaining TTL (key was just written)")
                .isNotNull();
        long ttl = ttlSeconds;
        long twentyFourHours = IdempotencyConfig.IDEMPOTENCY_TTL.toSeconds();
        long twentyThreeHours = twentyFourHours - 60 * 60; // 23h floor — leaves plenty of slack.

        assertThat(ttl)
                .as("TTL must be greater than 23h and at most the configured 24h ("
                        + twentyFourHours + "s)")
                .isGreaterThan(twentyThreeHours)
                .isLessThanOrEqualTo(twentyFourHours);
    }

    @Test
    @DisplayName("10 threads racing on the same key produce exactly one winner; losers all see the same snapshot")
    void concurrentSetNxYieldsExactlyOneWinner() throws Exception {
        String key = "race-" + UUID.randomUUID();
        int threadCount = 10;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Set<String> observedLoserSnapshots = java.util.Collections.synchronizedSet(new HashSet<>());
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final String mySnapshot = "snapshot-from-thread-" + i;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted at latch", e);
                    }
                    Optional<String> result = store.putIfAbsent(key, mySnapshot);
                    if (result.isEmpty()) {
                        winners.incrementAndGet();
                    } else {
                        observedLoserSnapshots.add(result.get());
                    }
                }, pool));
            }
            start.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get())
                .as("Redis SETNX must pick exactly one winner across " + threadCount + " concurrent calls")
                .isEqualTo(1);

        // Every loser must have observed the SAME snapshot — the one the winner stored.
        // We don't know which thread won, but the loser-observed set must be a singleton
        // and must equal whatever store.get(key) returns now.
        String winnerSnapshot = store.get(key).orElseThrow(() ->
                new AssertionError("store.get must return the winner's snapshot post-race"));
        assertThat(observedLoserSnapshots)
                .as("all 9 losers must replay the same winner snapshot")
                .containsExactly(winnerSnapshot);
    }
}
